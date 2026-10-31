package com.investary.research.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;

import com.investary.common.web.UrlCodec;
import com.investary.document.application.PdfSignatureValidator;

/**
 * Fetches a research PDF from an untrusted URL into a temporary file.
 * <ul>
 *   <li>every URL, including each redirect target, is checked by {@link UrlPolicy}; redirects are followed manually</li>
 *   <li>connections only go to addresses approved by {@link RestrictedAddressDnsResolver}</li>
 *   <li>connect/read timeouts, an overall deadline and a size cap bound every download</li>
 *   <li>the body is streamed to disk and accepted only if it carries a PDF signature</li>
 * </ul>
 * The caller owns the returned temporary file and must delete it.
 */
@Component
public class ReportDownloader {

    private static final Logger log = LoggerFactory.getLogger(ReportDownloader.class);
    private static final int BUFFER_SIZE = 16 * 1024;

    private final CloseableHttpClient client;
    private final UrlPolicy policy;
    private final DownloadProperties properties;

    public ReportDownloader(@Qualifier("reportDownloadHttpClient") CloseableHttpClient client, UrlPolicy policy,
                            DownloadProperties properties) {
        this.client = client;
        this.policy = policy;
        this.properties = properties;
    }

    public DownloadedFile download(String url) {
        long deadline = System.nanoTime() + properties.maxDuration().toNanos();
        URI current = policy.check(url);
        for (int redirects = 0; ; redirects++) {
            checkDeadline(deadline);
            Step step = fetch(current, deadline);
            if (step instanceof Done done) {
                return done.file();
            }
            if (redirects >= properties.maxRedirects()) {
                throw DownloadException.permanent("Too many redirects (more than " + properties.maxRedirects() + ")");
            }
            URI next;
            try {
                next = current.resolve(((Redirect) step).location().strip());
            } catch (IllegalArgumentException e) {
                throw DownloadException.permanent("The server redirected to an invalid URL");
            }
            policy.check(next);
            log.debug("Following redirect from={} to={}", current.getHost(), next.getHost());
            current = next;
        }
    }

    private Step fetch(URI uri, long deadline) {
        HttpGet request = new HttpGet(uri);
        request.setHeader(HttpHeaders.ACCEPT, "application/pdf, application/octet-stream;q=0.9, */*;q=0.1");
        try {
            return client.execute(request, response -> handle(uri, response, deadline));
        } catch (DownloadException e) {
            throw e;
        } catch (BlockedAddressException e) {
            throw DownloadException.blocked(e.getMessage());
        } catch (SocketTimeoutException e) {
            throw DownloadException.transientFailure("The server did not respond in time", e);
        } catch (UnknownHostException e) {
            throw DownloadException.transientFailure("The host could not be resolved", e);
        } catch (SSLException e) {
            throw DownloadException.permanent("Secure connection failed: " + e.getMessage());
        } catch (IOException e) {
            throw DownloadException.transientFailure("Network error: " + e.getClass().getSimpleName(), e);
        }
    }

    private Step handle(URI uri, ClassicHttpResponse response, long deadline) throws IOException {
        int code = response.getCode();
        if (code >= 300 && code < 400 && code != 304) {
            Header location = response.getFirstHeader(HttpHeaders.LOCATION);
            if (location == null || location.getValue().isBlank()) {
                throw DownloadException.permanent("Redirect (HTTP " + code + ") without a Location header");
            }
            return new Redirect(location.getValue());
        }
        if (code == 404 || code == 410) {
            throw DownloadException.permanent("The link has expired or the report no longer exists (HTTP " + code + ")");
        }
        if (code == 401 || code == 403) {
            throw DownloadException.permanent("Access denied (HTTP " + code + "); the report may require a login");
        }
        if (code == 408 || code == 429 || code >= 500) {
            throw DownloadException.transientFailure("The server is temporarily unavailable (HTTP " + code + ")", null);
        }
        if (code != 200) {
            throw DownloadException.permanent("Unexpected HTTP status " + code);
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            throw DownloadException.permanent("The server returned an empty response");
        }
        long maxBytes = properties.maxSize().toBytes();
        if (entity.getContentLength() > maxBytes) {
            throw DownloadException.permanent("The file is larger than the " + properties.maxSize().toMegabytes() + " MB limit");
        }

        Files.createDirectories(properties.tempDir());
        Path temp = Files.createTempFile(properties.tempDir(), "download-", ".part");
        try {
            long size = copy(entity, temp, maxBytes, deadline);
            if (size == 0) {
                throw DownloadException.permanent("The server returned an empty file");
            }
            try (InputStream in = Files.newInputStream(temp)) {
                if (!PdfSignatureValidator.isPdf(in)) {
                    String type = entity.getContentType() == null ? "unknown" : entity.getContentType();
                    throw DownloadException.permanent("The link did not return a PDF (content type: " + type + ")");
                }
            }
            return new Done(new DownloadedFile(temp, fileName(uri, response), size, uri.toString()));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private long copy(HttpEntity entity, Path target, long maxBytes, long deadline) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = entity.getContent(); OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw DownloadException.permanent("The file is larger than the " + properties.maxSize().toMegabytes() + " MB limit");
                }
                checkDeadline(deadline);
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw DownloadException.transientFailure("The download took longer than " + properties.maxDuration().toSeconds() + " seconds", null);
        }
    }

    private static String fileName(URI uri, ClassicHttpResponse response) {
        Header disposition = response.getFirstHeader("Content-Disposition");
        if (disposition != null) {
            try {
                String name = ContentDisposition.parse(disposition.getValue()).getFilename();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (IllegalArgumentException e) {
                // fall back to the URL path
            }
        }
        String path = uri.getRawPath() == null ? "" : UrlCodec.percentDecode(uri.getRawPath());
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? null : name;
    }

    /**
     * @param path     temporary file holding the validated PDF; the caller must delete it
     * @param fileName name suggested by the server or the URL, unsanitized
     * @param finalUrl URL that served the content, after redirects
     */
    public record DownloadedFile(Path path, String fileName, long size, String finalUrl) {
    }

    private sealed interface Step permits Redirect, Done {
    }

    private record Redirect(String location) implements Step {
    }

    private record Done(DownloadedFile file) implements Step {
    }
}

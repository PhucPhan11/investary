package com.investary.research.download;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.investary.document.TestPdfs;
import com.investary.research.download.DownloadException.Kind;
import com.investary.research.link.BrokerageRegistry;
import com.investary.research.link.ResearchProperties;

class ReportDownloaderTest {

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    Path tempDir;

    private final List<ReportDownloader.DownloadedFile> downloaded = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        for (var file : downloaded) {
            Files.deleteIfExists(file.path());
        }
        try (Stream<Path> leftovers = Files.list(tempDir)) {
            assertThat(leftovers).as("temporary files are always removed").isEmpty();
        }
    }

    @Test
    void downloadsValidPdfAndUsesServerFileName() throws IOException {
        byte[] pdf = TestPdfs.pdf("ok");
        server.stubFor(get(urlEqualTo("/reports/q3.pdf")).willReturn(aResponse()
                .withHeader("Content-Type", "application/pdf")
                .withHeader("Content-Disposition", "attachment; filename=\"VNM Q3 2026.pdf\"")
                .withBody(pdf)));

        var file = download(localPolicy(), "/reports/q3.pdf");

        assertThat(Files.readAllBytes(file.path())).isEqualTo(pdf);
        assertThat(file.fileName()).isEqualTo("VNM Q3 2026.pdf");
        assertThat(file.size()).isEqualTo(pdf.length);
    }

    @Test
    void followsRedirectsAndChecksEveryHop() {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withStatus(302).withHeader("Location", "/b")));
        server.stubFor(get(urlEqualTo("/b")).willReturn(aResponse().withStatus(301).withHeader("Location", url("/final/report.pdf"))));
        server.stubFor(get(urlEqualTo("/final/report.pdf")).willReturn(aResponse().withBody(TestPdfs.pdf("redirected"))));

        var file = download(localPolicy(), "/a");

        assertThat(file.finalUrl()).endsWith("/final/report.pdf");
        assertThat(file.fileName()).isEqualTo("report.pdf");
    }

    @Test
    void blocksRedirectToHostOutsideAllowlist() {
        server.stubFor(get(urlEqualTo("/r")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://evil.example/steal.pdf")));

        assertFails(localPolicy(), "/r", Kind.BLOCKED, "not on the download allowlist");
    }

    @Test
    void blocksRedirectToCloudMetadataAddress() {
        server.stubFor(get(urlEqualTo("/r")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://169.254.169.254/latest/meta-data")));

        assertFails(localPolicy(), "/r", Kind.BLOCKED, "allowlist");
    }

    @Test
    void stopsRedirectLoops() {
        server.stubFor(get(urlEqualTo("/loop")).willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));

        assertFails(localPolicy(), "/loop", Kind.PERMANENT, "Too many redirects");
    }

    @Test
    void expiredLinkIsPermanentFailure() {
        server.stubFor(get(urlEqualTo("/gone.pdf")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/removed.pdf")).willReturn(aResponse().withStatus(410)));

        assertFails(localPolicy(), "/gone.pdf", Kind.PERMANENT, "expired");
        assertFails(localPolicy(), "/removed.pdf", Kind.PERMANENT, "expired");
    }

    @Test
    void serverErrorIsTransient() {
        server.stubFor(get(urlEqualTo("/busy.pdf")).willReturn(aResponse().withStatus(503)));

        assertFails(localPolicy(), "/busy.pdf", Kind.TRANSIENT, "temporarily unavailable");
    }

    @Test
    void htmlPageIsRejectedEvenIfUrlEndsWithPdf() {
        server.stubFor(get(urlEqualTo("/login.pdf")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html; charset=utf-8").withBody("<html><body>Please log in</body></html>")));

        assertFails(localPolicy(), "/login.pdf", Kind.PERMANENT, "did not return a PDF");
    }

    @Test
    void rejectsFilesAboveSizeLimitByHeaderAndWhileStreaming() {
        byte[] large = new byte[4096];
        System.arraycopy(TestPdfs.pdf("big"), 0, large, 0, 20);
        server.stubFor(get(urlEqualTo("/big.pdf")).willReturn(aResponse().withBody(large)));
        server.stubFor(get(urlEqualTo("/big-chunked.pdf")).willReturn(aResponse().withBody(large).withChunkedDribbleDelay(4, 40)));

        DownloadProperties small = properties(true, DataSize.ofKilobytes(1), Duration.ofSeconds(5));
        assertFails(small, "/big.pdf", Kind.PERMANENT, "larger than");
        assertFails(small, "/big-chunked.pdf", Kind.PERMANENT, "larger than");
    }

    @Test
    void slowServerTimesOutAsTransientFailure() {
        server.stubFor(get(urlEqualTo("/slow.pdf")).willReturn(aResponse().withBody(TestPdfs.pdf("slow")).withFixedDelay(1500)));

        assertFails(properties(true, DataSize.ofMegabytes(1), Duration.ofMillis(300)), "/slow.pdf", Kind.TRANSIENT, "did not respond in time");
    }

    @Test
    void blocksLocalhostWhenPrivateNetworksAreNotAllowed() {
        server.stubFor(get(urlEqualTo("/internal.pdf")).willReturn(aResponse().withBody(TestPdfs.pdf("internal"))));

        assertFails(properties(false, DataSize.ofMegabytes(1), Duration.ofSeconds(5)), "/internal.pdf", Kind.BLOCKED, "restricted network address");
        server.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/internal.pdf")));
    }

    private ReportDownloader.DownloadedFile download(DownloadProperties properties, String path) {
        var file = downloader(properties).download(url(path));
        downloaded.add(file);
        return file;
    }

    private void assertFails(DownloadProperties properties, String path, Kind kind, String message) {
        assertThatThrownBy(() -> downloader(properties).download(url(path)))
                .isInstanceOfSatisfying(DownloadException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(kind);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    private ReportDownloader downloader(DownloadProperties properties) {
        var registry = new BrokerageRegistry(new ResearchProperties(List.of()));
        return new ReportDownloader(new DownloadConfiguration().reportDownloadHttpClient(properties),
                new UrlPolicy(properties, registry), properties);
    }

    private DownloadProperties localPolicy() {
        return properties(true, DataSize.ofMegabytes(1), Duration.ofSeconds(5));
    }

    /** Local test server: http, localhost and random ports must be allowed explicitly. */
    private DownloadProperties properties(boolean allowPrivateNetworks, DataSize maxSize, Duration readTimeout) {
        return new DownloadProperties(true, allowPrivateNetworks, true, List.of("localhost"), Duration.ofSeconds(2), readTimeout,
                Duration.ofSeconds(10), maxSize, 3, null, tempDir, 3, Duration.ofSeconds(1), null);
    }

    private static String url(String path) {
        return "http://localhost:" + server.getPort() + path;
    }
}

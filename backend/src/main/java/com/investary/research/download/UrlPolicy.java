package com.investary.research.download;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.investary.research.link.BrokerageRegistry;

/**
 * Decides whether Investary may fetch a URL. Applied to the requested URL and again to every
 * redirect target. Address-level checks (private networks, DNS rebinding) happen in
 * {@link RestrictedAddressDnsResolver} at connection time.
 */
@Component
public class UrlPolicy {

    private final DownloadProperties properties;
    private final List<String> allowedDomains;

    public UrlPolicy(DownloadProperties properties, BrokerageRegistry brokerages) {
        this.properties = properties;
        List<String> domains = new ArrayList<>(brokerages.domains());
        domains.addAll(properties.additionalAllowedDomains());
        this.allowedDomains = List.copyOf(domains);
    }

    /**
     * @throws DownloadException with kind {@code BLOCKED} when the URL is not allowed
     */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw DownloadException.blocked("The URL is not valid");
        }
        check(uri);
        return uri;
    }

    public void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if (!https && !(scheme.equals("http") && properties.allowHttp())) {
            throw DownloadException.blocked("Only HTTPS links can be downloaded (got '" + (scheme.isEmpty() ? "none" : scheme) + "')");
        }
        if (uri.getRawUserInfo() != null) {
            throw DownloadException.blocked("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw DownloadException.blocked("The URL has no host");
        }
        String asciiHost;
        try {
            asciiHost = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw DownloadException.blocked("The host name is not valid");
        }
        int port = uri.getPort();
        if (port != -1 && !properties.allowAnyPort() && port != (https ? 443 : 80)) {
            throw DownloadException.blocked("Non-standard port " + port + " is not allowed");
        }
        if (allowedDomains.stream().noneMatch(domain -> BrokerageRegistry.matches(asciiHost, domain))) {
            throw DownloadException.blocked("Domain " + asciiHost + " is not on the download allowlist");
        }
    }

    public List<String> allowedDomains() {
        return allowedDomains;
    }
}

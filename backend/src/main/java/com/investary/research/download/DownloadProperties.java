package com.investary.research.download;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Safety and reliability settings for downloading research PDFs linked from emails.
 * The defaults are restrictive; relax them only for local testing.
 *
 * @param allowHttp                  allow plain-http URLs (default: https only)
 * @param allowPrivateNetworks       allow destinations that resolve to loopback/private/link-local addresses (never in production)
 * @param allowAnyPort               allow ports other than 443 (and 80 when http is allowed)
 * @param additionalAllowedDomains   domains allowed in addition to the configured brokerage domains
 * @param maxDuration                overall deadline for one download, including redirects
 * @param tempDir                    where partial downloads are written before validation
 */
@ConfigurationProperties("investary.download")
public record DownloadProperties(
        boolean allowHttp,
        boolean allowPrivateNetworks,
        boolean allowAnyPort,
        List<String> additionalAllowedDomains,
        Duration connectTimeout,
        Duration readTimeout,
        Duration maxDuration,
        DataSize maxSize,
        int maxRedirects,
        String userAgent,
        Path tempDir,
        int maxAttempts,
        Duration retryBackoff,
        Worker worker) {

    public DownloadProperties {
        additionalAllowedDomains = additionalAllowedDomains == null ? List.of() : List.copyOf(additionalAllowedDomains);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        maxDuration = maxDuration == null ? Duration.ofMinutes(2) : maxDuration;
        maxSize = maxSize == null ? DataSize.ofMegabytes(50) : maxSize;
        maxRedirects = maxRedirects <= 0 ? 5 : maxRedirects;
        userAgent = userAgent == null ? "Investary/0.2 (personal research library)" : userAgent;
        tempDir = tempDir == null ? Path.of(System.getProperty("java.io.tmpdir"), "investary-downloads") : tempDir;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofMinutes(1) : retryBackoff;
        worker = worker == null ? new Worker(true, 2, Duration.ofSeconds(2), Duration.ofMinutes(5)) : worker;
    }

    /**
     * @param lease how long a claimed job may run before another poll considers it abandoned and re-queues it
     */
    public record Worker(boolean enabled, int concurrency, Duration pollInterval, Duration lease) {

        public Worker {
            concurrency = concurrency <= 0 ? 2 : concurrency;
            pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
            lease = lease == null ? Duration.ofMinutes(5) : lease;
        }
    }
}

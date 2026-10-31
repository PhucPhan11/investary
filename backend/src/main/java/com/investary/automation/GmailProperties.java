package com.investary.automation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gmail integration (Phase 5 / Milestone 1). Client credentials and the token key come only from the
 * environment; the Google endpoint URLs are configurable so tests can point them at a mock server.
 *
 * @param clientId       OAuth client id (Web application client)
 * @param clientSecret   OAuth client secret
 * @param tokenKey       base64-encoded 32-byte AES key that encrypts the stored refresh token
 * @param redirectUri    callback URI registered for the client; must match exactly
 * @param uiRedirectUrl  where the callback sends the browser afterwards
 * @param maxRetries     retries of a Gmail call after 429 / 5xx / rate-limit 403
 * @param retryBackoff   first retry delay; doubles per attempt
 * @param maxRetryWait   upper bound for one retry delay, including {@code Retry-After}
 * @param stateTtl       how long a started authorization may take
 */
@ConfigurationProperties("investary.gmail")
public record GmailProperties(
        String clientId,
        String clientSecret,
        String tokenKey,
        String redirectUri,
        String uiRedirectUrl,
        String authUri,
        String tokenUri,
        String revokeUri,
        String apiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRetries,
        Duration retryBackoff,
        Duration maxRetryWait,
        Duration stateTtl,
        Sync sync) {

    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    public GmailProperties {
        redirectUri = orDefault(redirectUri, "http://localhost:8080/api/v1/integrations/gmail/callback");
        uiRedirectUrl = orDefault(uiRedirectUrl, "http://localhost:4200/automation");
        authUri = orDefault(authUri, "https://accounts.google.com/o/oauth2/v2/auth");
        tokenUri = orDefault(tokenUri, "https://oauth2.googleapis.com/token");
        revokeUri = orDefault(revokeUri, "https://oauth2.googleapis.com/revoke");
        apiBaseUrl = orDefault(apiBaseUrl, "https://gmail.googleapis.com");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        maxRetries = maxRetries < 0 ? 0 : maxRetries;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(1) : retryBackoff;
        maxRetryWait = maxRetryWait == null ? Duration.ofSeconds(30) : maxRetryWait;
        stateTtl = stateTtl == null ? Duration.ofMinutes(10) : stateTtl;
        sync = sync == null ? new Sync(true, null, 0, 0, 0, null, 0) : sync;
    }

    /** Client id, secret and token key are all set. */
    public boolean credentialsConfigured() {
        return hasText(clientId) && hasText(clientSecret) && hasText(tokenKey);
    }

    /**
     * @param schedulerEnabled         run scheduled syncs
     * @param interval                 delay between scheduled syncs
     * @param initialLookbackDays      window of the initial sync
     * @param maxMessagesPerRun        new messages fetched from Gmail per run; the rest continue next run
     * @param recoveryMaxLookbackDays  upper bound of the window used when the history cursor has expired
     * @param lockLease                how long a sync holds the account lock without renewing it
     * @param pageSize                 {@code maxResults} for list calls
     */
    public record Sync(boolean schedulerEnabled, Duration interval, int initialLookbackDays, int maxMessagesPerRun,
                       int recoveryMaxLookbackDays, Duration lockLease, int pageSize) {

        public Sync {
            interval = interval == null ? Duration.ofMinutes(30) : interval;
            initialLookbackDays = initialLookbackDays <= 0 ? 30 : initialLookbackDays;
            maxMessagesPerRun = maxMessagesPerRun <= 0 ? 200 : maxMessagesPerRun;
            recoveryMaxLookbackDays = recoveryMaxLookbackDays <= 0 ? 30 : recoveryMaxLookbackDays;
            lockLease = lockLease == null ? Duration.ofMinutes(10) : lockLease;
            pageSize = pageSize <= 0 ? 100 : Math.min(pageSize, 500);
        }
    }

    private static String orDefault(String value, String fallback) {
        return hasText(value) ? value.strip() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

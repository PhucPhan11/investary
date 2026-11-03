package com.investary.common.error;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Safe, bounded error summaries for persisted job state (no URLs, which can carry tokens in query strings,
 * no line breaks or stack traces), and the shared retry backoff of the background job queues.
 */
public final class ErrorSummaries {

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");
    private static final int MAX_SUMMARY = 300;

    private ErrorSummaries() {
    }

    /** Removes URLs and line breaks and limits the length. */
    public static String safe(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = URL.matcher(message).replaceAll("[url]").replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 1) + "…";
    }

    /**
     * Exponential backoff {@code base * 2^(attempt-1)} (capped at 2^10) with ±50% jitter, so retries of many
     * jobs that failed together do not hit a provider at the same moment.
     *
     * @param random a value in [0, 1)
     */
    public static Duration backoff(Duration base, int attempt, double random) {
        long millis = base.toMillis() * (1L << Math.min(Math.max(attempt - 1, 0), 10));
        double factor = 0.5 + random;
        return Duration.ofMillis(Math.max(1, Math.round(millis * factor)));
    }
}

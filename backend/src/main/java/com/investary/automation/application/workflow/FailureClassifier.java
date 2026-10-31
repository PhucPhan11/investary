package com.investary.automation.application.workflow;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.AiExceptions.AiTimeoutException;
import com.investary.knowledge.embedding.EmbeddingException;
import com.investary.knowledge.embedding.EmbeddingUnavailableException;

/**
 * Sorts failures into transient (retry with backoff) and permanent (fail now, manual retry only), and turns
 * them into safe summaries: no stack traces, no URLs (they can carry tokens in query strings), bounded length.
 */
public final class FailureClassifier {

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");
    private static final int MAX_SUMMARY = 300;

    private FailureClassifier() {
    }

    public static StageResult.Failed classify(RuntimeException e) {
        if (e instanceof AiTimeoutException) {
            return new StageResult.Failed("AI_TIMEOUT", "The AI model did not answer in time", true);
        }
        if (e instanceof AiProviderException provider) {
            return new StageResult.Failed("AI_PROVIDER_ERROR", safe(provider.getMessage()), provider.isRetryable());
        }
        if (e instanceof AiNotConfiguredException || e instanceof EmbeddingUnavailableException) {
            return new StageResult.Failed("AI_NOT_CONFIGURED", "No AI model is configured", false);
        }
        if (e instanceof EmbeddingException embedding) {
            return new StageResult.Failed("EMBEDDING_FAILED", safe(embedding.getMessage()), !embedding.isPermanent());
        }
        if (e instanceof ApiException api) {
            if (api.getStatus() == HttpStatus.NOT_FOUND) {
                return new StageResult.Failed("SOURCE_DELETED", "The source or portfolio no longer exists", false);
            }
            boolean retryable = api.getStatus().is5xxServerError() || api.getStatus() == HttpStatus.TOO_MANY_REQUESTS;
            return new StageResult.Failed(api.getCode().name(), safe(api.getMessage()), retryable);
        }
        if (e instanceof TransientDataAccessException || e instanceof DataAccessResourceFailureException) {
            return new StageResult.Failed("DATABASE_UNAVAILABLE", "The database was temporarily unavailable", true);
        }
        // Unknown failures are retried a bounded number of times, like download and indexing jobs.
        return new StageResult.Failed("INTERNAL_ERROR", "Unexpected error (" + e.getClass().getSimpleName() + ")", true);
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

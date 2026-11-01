package com.investary.automation.application.workflow;

import java.time.Duration;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorSummaries;
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

    /** Removes URLs and line breaks and limits the length ({@link ErrorSummaries#safe}). */
    public static String safe(String message) {
        return ErrorSummaries.safe(message);
    }

    /** See {@link ErrorSummaries#backoff}. */
    public static Duration backoff(Duration base, int attempt, double random) {
        return ErrorSummaries.backoff(base, attempt, random);
    }
}

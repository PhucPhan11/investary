package com.investary.knowledge;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/**
 * Failures of the AI provider integration. Messages never include prompts, source text or secrets.
 */
public final class AiExceptions {

    private AiExceptions() {
    }

    public static class AiNotConfiguredException extends ApiException {
        public AiNotConfiguredException(String capability) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.AI_NOT_CONFIGURED, "AI provider not configured",
                    "No " + capability + " model is configured. Set INVESTARY_AI_CHAT_PROVIDER and "
                            + "INVESTARY_AI_EMBEDDING_PROVIDER (see README, Research assistant).");
        }
    }

    public static class AiProviderException extends ApiException {
        private final boolean retryable;

        public AiProviderException(String detail) {
            this(detail, true);
        }

        /**
         * @param retryable false when the provider rejected the request itself (e.g. an unknown model),
         *                  so repeating it cannot succeed
         */
        public AiProviderException(String detail, boolean retryable) {
            super(HttpStatus.BAD_GATEWAY, ErrorCode.AI_PROVIDER_ERROR, "AI provider error", detail);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    public static class AiTimeoutException extends ApiException {
        public AiTimeoutException(long seconds) {
            super(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.AI_TIMEOUT, "AI provider timeout",
                    "The AI model did not answer within " + seconds + " seconds");
        }
    }
}

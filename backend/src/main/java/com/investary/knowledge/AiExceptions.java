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
        public AiProviderException(String detail) {
            super(HttpStatus.BAD_GATEWAY, ErrorCode.AI_PROVIDER_ERROR, "AI provider error", detail);
        }
    }

    public static class AiTimeoutException extends ApiException {
        public AiTimeoutException(long seconds) {
            super(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.AI_TIMEOUT, "AI provider timeout",
                    "The AI model did not answer within " + seconds + " seconds");
        }
    }
}

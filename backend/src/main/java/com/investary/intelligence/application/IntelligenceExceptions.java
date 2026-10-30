package com.investary.intelligence.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public final class IntelligenceExceptions {

    private IntelligenceExceptions() {
    }

    public static class AssessmentNotFoundException extends ApiException {
        public AssessmentNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.ASSESSMENT_NOT_FOUND, "Assessment not found", "Impact assessment " + id + " was not found");
        }
    }

    /**
     * A portfolio-aware request did not say which portfolio to use. Investary never picks one silently.
     */
    public static class PortfolioRequiredException extends ApiException {

        private final int portfolioCount;

        public PortfolioRequiredException(int portfolioCount) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.PORTFOLIO_REQUIRED, "Portfolio required",
                    portfolioCount == 0 ? "Create a portfolio before asking portfolio-aware questions"
                            : "Choose the portfolio to use (" + portfolioCount + " available)");
            this.portfolioCount = portfolioCount;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("portfolioCount", portfolioCount);
        }
    }
}

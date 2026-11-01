package com.investary.intelligence.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public final class BriefExceptions {

    private BriefExceptions() {
    }

    public static class BriefNotFoundException extends ApiException {
        public BriefNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.BRIEF_NOT_FOUND, "Brief not found", "Brief " + id + " was not found");
        }
    }

    public static class BriefJobNotFoundException extends ApiException {
        public BriefJobNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.BRIEF_JOB_NOT_FOUND, "Brief job not found", "Brief job " + id + " was not found");
        }
    }

    public static class BriefJobNotRetryableException extends ApiException {
        public BriefJobNotRetryableException(String status) {
            super(HttpStatus.CONFLICT, ErrorCode.JOB_NOT_RETRYABLE, "Job not retryable",
                    "Only failed brief jobs can be retried; this job is " + status);
        }
    }

    public static class BriefsDisabledException extends ApiException {
        public BriefsDisabledException() {
            super(HttpStatus.CONFLICT, ErrorCode.BRIEFS_DISABLED, "Briefs disabled",
                    "Daily briefs are disabled (investary.brief.enabled=false)");
        }
    }
}

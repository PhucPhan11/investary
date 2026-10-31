package com.investary.automation.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/**
 * Expected failures of the Gmail integration and source rule APIs.
 */
public final class AutomationExceptions {

    private AutomationExceptions() {
    }

    public static class GmailNotConfiguredException extends ApiException {
        public GmailNotConfiguredException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.GMAIL_NOT_CONFIGURED, "Gmail not configured",
                    "Set INVESTARY_GMAIL_CLIENT_ID, INVESTARY_GMAIL_CLIENT_SECRET and INVESTARY_GMAIL_TOKEN_KEY, then restart the backend");
        }
    }

    public static class GmailNotConnectedException extends ApiException {
        public GmailNotConnectedException(String detail) {
            super(HttpStatus.CONFLICT, ErrorCode.GMAIL_NOT_CONNECTED, "Gmail not connected", detail);
        }
    }

    public static class SyncInProgressException extends ApiException {
        public SyncInProgressException() {
            super(HttpStatus.CONFLICT, ErrorCode.GMAIL_SYNC_IN_PROGRESS, "Sync in progress",
                    "A Gmail sync is already running for this account");
        }
    }

    public static class SyncRunNotFoundException extends ApiException {
        public SyncRunNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.SYNC_RUN_NOT_FOUND, "Sync run not found", "Sync run " + id + " was not found");
        }
    }

    public static class SourceRuleNotFoundException extends ApiException {
        public SourceRuleNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.SOURCE_RULE_NOT_FOUND, "Source rule not found", "Source rule " + id + " was not found");
        }
    }

    public static class DuplicateSourceRuleException extends ApiException {
        public DuplicateSourceRuleException(String name) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_SOURCE_RULE, "Duplicate source rule",
                    "A source rule named '" + name + "' already exists");
        }
    }

    /** Field errors found by the service (normalisation and Gmail query checks). */
    public static class InvalidSourceRuleException extends ApiException {
        private final Map<String, String> errors;

        public InvalidSourceRuleException(Map<String, String> errors) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Validation failed", "One or more fields are invalid");
            this.errors = Map.copyOf(errors);
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("errors", errors);
        }
    }
}

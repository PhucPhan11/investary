package com.investary.automation.google;

/**
 * Failures of Google's OAuth and Gmail endpoints, classified for the sync engine. Messages never
 * contain tokens, codes or message content.
 */
public final class GoogleExceptions {

    private GoogleExceptions() {
    }

    /** Base type. */
    public static class GoogleApiException extends RuntimeException {
        private final String code;

        public GoogleApiException(String code, String message) {
            super(message);
            this.code = code;
        }

        public GoogleApiException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /** Stable code stored with the sync run (e.g. {@code RATE_LIMITED}). */
        public String code() {
            return code;
        }
    }

    /** The refresh token is no longer valid ({@code invalid_grant}): the user must reconnect. */
    public static class InvalidGrantException extends GoogleApiException {
        public InvalidGrantException() {
            super("RECONNECT_REQUIRED", "Google rejected the stored authorization (invalid_grant); reconnect Gmail");
        }
    }

    /** A temporary failure that still failed after the bounded retries (429, 5xx, timeouts). */
    public static class GoogleTransientException extends GoogleApiException {
        public GoogleTransientException(String code, String message) {
            super(code, message);
        }

        public GoogleTransientException(String code, String message, Throwable cause) {
            super(code, message, cause);
        }
    }

    /** {@code history.list} answered 404: the start history id is too old or invalid. */
    public static class HistoryExpiredException extends GoogleApiException {
        public HistoryExpiredException() {
            super("HISTORY_EXPIRED", "The Gmail history cursor has expired");
        }
    }

    /** A message disappeared between listing and fetching it (deleted by the user). */
    public static class MessageNotFoundException extends GoogleApiException {
        public MessageNotFoundException() {
            super("MESSAGE_NOT_FOUND", "The Gmail message no longer exists");
        }
    }
}

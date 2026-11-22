package com.investary.notebook.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/**
 * Expected failures of research notebooks.
 */
public final class NotebookExceptions {

    private NotebookExceptions() {
    }

    public static class NotebookNotFoundException extends ApiException {
        public NotebookNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.NOTEBOOK_NOT_FOUND, "Notebook not found", "Notebook " + id + " was not found");
        }
    }

    public static class NotebookConflictException extends ApiException {
        private final Integer currentVersion;

        public NotebookConflictException(Integer currentVersion) {
            super(HttpStatus.CONFLICT, ErrorCode.NOTEBOOK_CONFLICT, "Notebook changed",
                    "The notebook was changed elsewhere; reload it and try again");
            this.currentVersion = currentVersion;
        }

        @Override
        public Map<String, Object> getProperties() {
            return currentVersion == null ? Map.of() : Map.of("currentVersion", currentVersion);
        }
    }

    /** The notebook has no searchable source, so there is nothing to answer from. */
    public static class NotebookEmptyException extends ApiException {
        public NotebookEmptyException() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.NOTEBOOK_EMPTY, "Notebook has no searchable sources",
                    "Add documents or emails that have been indexed to this notebook first");
        }
    }

    public static class NotebookSourceLimitException extends ApiException {
        public NotebookSourceLimitException(int limit) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.NOTEBOOK_SOURCE_LIMIT, "Too many sources",
                    "A notebook can hold at most " + limit + " documents and emails");
        }
    }

    public static class NoteNotFoundException extends ApiException {
        public NoteNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.NOTE_NOT_FOUND, "Note not found", "Note " + id + " was not found");
        }
    }

    public static class NoteLimitReachedException extends ApiException {
        public NoteLimitReachedException(int limit) {
            super(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.NOTE_LIMIT_REACHED, "Daily note limit reached",
                    "At most " + limit + " notes can be generated per day; try again tomorrow or raise investary.notebook.daily-note-limit");
        }
    }

    public static class NoteNotRetryableException extends ApiException {
        public NoteNotRetryableException() {
            super(HttpStatus.CONFLICT, ErrorCode.NOTE_NOT_RETRYABLE, "Note cannot be retried", "Only a failed note can be retried");
        }
    }

    public static class NoteTickerRequiredException extends ApiException {
        public NoteTickerRequiredException(String kind) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Ticker required",
                    "A valid ticker (1-15 letters or digits) is required for " + kind + " notes");
        }
    }
}

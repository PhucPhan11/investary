package com.investary.email.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/**
 * Expected failures of the email API.
 */
public final class EmailExceptions {

    private EmailExceptions() {
    }

    public static class InvalidEmailException extends ApiException {
        public InvalidEmailException(String message) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_EMAIL, "Invalid email", message);
        }
    }

    public static class EmailTooLargeException extends ApiException {
        public EmailTooLargeException(long maxMegabytes) {
            super(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, "Email too large",
                    "EML files larger than " + maxMegabytes + " MB are not accepted");
        }
    }

    public static class DuplicateEmailException extends ApiException {
        private final UUID existingEmailId;

        public DuplicateEmailException(UUID existingEmailId) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_EMAIL, "Duplicate email", "This email file has already been imported");
            this.existingEmailId = existingEmailId;
        }

        public UUID getExistingEmailId() {
            return existingEmailId;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("existingEmailId", existingEmailId);
        }
    }

    public static class EmailNotFoundException extends ApiException {
        public EmailNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.EMAIL_NOT_FOUND, "Email not found", "Email " + id + " was not found");
        }
    }
}

package com.investary.journal.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;
import com.investary.journal.domain.DecisionStatus;
import com.investary.journal.domain.EvidenceKind;

/**
 * Expected failures of the decision journal.
 */
public final class JournalExceptions {

    private JournalExceptions() {
    }

    public static class DecisionNotFoundException extends ApiException {
        public DecisionNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.DECISION_NOT_FOUND, "Decision not found", "Decision " + id + " was not found");
        }
    }

    public static class DecisionConflictException extends ApiException {
        private final Integer currentVersion;

        public DecisionConflictException(Integer currentVersion) {
            super(HttpStatus.CONFLICT, ErrorCode.DECISION_CONFLICT, "Decision changed",
                    "The decision was changed elsewhere; reload it and try again");
            this.currentVersion = currentVersion;
        }

        @Override
        public Map<String, Object> getProperties() {
            return currentVersion == null ? Map.of() : Map.of("currentVersion", currentVersion);
        }
    }

    public static class InvalidTransitionException extends ApiException {
        public InvalidTransitionException(String action, DecisionStatus status) {
            super(HttpStatus.CONFLICT, ErrorCode.INVALID_DECISION_TRANSITION, "Invalid status change",
                    "Cannot " + action + " a decision that is " + status);
        }

        public InvalidTransitionException(String detail) {
            super(HttpStatus.CONFLICT, ErrorCode.INVALID_DECISION_TRANSITION, "Invalid status change", detail);
        }
    }

    public static class FieldLockedException extends ApiException {
        private final List<String> fields;

        public FieldLockedException(List<String> fields) {
            super(HttpStatus.CONFLICT, ErrorCode.DECISION_FIELD_LOCKED, "Field locked",
                    "These fields are fixed once a decision is finalized: " + String.join(", ", fields)
                            + ". Cancel it and record a new decision instead.");
            this.fields = fields;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("fields", fields);
        }
    }

    /** A request that is well-formed but incomplete for the decision's type or status. */
    public static class InvalidDecisionException extends ApiException {
        public InvalidDecisionException(String detail) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Invalid decision", detail);
        }
    }

    public static class EvidenceSourceNotFoundException extends ApiException {
        public EvidenceSourceNotFoundException(EvidenceKind kind, UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.EVIDENCE_SOURCE_NOT_FOUND, "Evidence source not found",
                    "No " + kind + " with id " + id + " is available");
        }
    }

    public static class DuplicateEvidenceException extends ApiException {
        public DuplicateEvidenceException(EvidenceKind kind, UUID id) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_EVIDENCE, "Duplicate evidence",
                    kind + " " + id + " is already attached to this decision");
        }
    }

    public static class EvidenceNotFoundException extends ApiException {
        public EvidenceNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.EVIDENCE_NOT_FOUND, "Evidence not found", "Evidence " + id + " was not found");
        }
    }

    public static class InvalidTransactionLinkException extends ApiException {
        public InvalidTransactionLinkException(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.TRANSACTION_LINK_INVALID, "Transaction cannot be linked", detail);
        }
    }

    public static class DuplicateTransactionLinkException extends ApiException {
        public DuplicateTransactionLinkException(UUID transactionId) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_TRANSACTION_LINK, "Duplicate link",
                    "Transaction " + transactionId + " is already linked to this decision");
        }
    }

    public static class TransactionLinkNotFoundException extends ApiException {
        public TransactionLinkNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.TRANSACTION_LINK_NOT_FOUND, "Link not found", "Transaction link " + id + " was not found");
        }
    }
}

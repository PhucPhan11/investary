package com.investary.common.error;

/**
 * Machine-readable error codes returned in the {@code code} property of problem details.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_DOCUMENT,
    DOCUMENT_NOT_FOUND,
    DUPLICATE_DOCUMENT,
    STORAGE_ERROR,
    INTERNAL_ERROR
}

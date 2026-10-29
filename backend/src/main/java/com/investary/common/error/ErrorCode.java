package com.investary.common.error;

/**
 * Machine-readable error codes returned in the {@code code} property of problem details.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_DOCUMENT,
    DOCUMENT_NOT_FOUND,
    DUPLICATE_DOCUMENT,
    INVALID_EMAIL,
    DUPLICATE_EMAIL,
    EMAIL_NOT_FOUND,
    LINK_NOT_FOUND,
    DOWNLOAD_NOT_ALLOWED,
    KNOWLEDGE_JOB_NOT_FOUND,
    AI_NOT_CONFIGURED,
    AI_PROVIDER_ERROR,
    AI_TIMEOUT,
    PAYLOAD_TOO_LARGE,
    STORAGE_ERROR,
    INTERNAL_ERROR
}

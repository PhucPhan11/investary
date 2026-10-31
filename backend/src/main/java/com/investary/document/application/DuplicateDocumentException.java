package com.investary.document.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class DuplicateDocumentException extends ApiException {

    private final UUID existingDocumentId;

    public DuplicateDocumentException(UUID existingDocumentId) {
        super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_DOCUMENT, "Duplicate document",
                "A document with identical content already exists");
        this.existingDocumentId = existingDocumentId;
    }

    public UUID getExistingDocumentId() {
        return existingDocumentId;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("existingDocumentId", existingDocumentId);
    }
}

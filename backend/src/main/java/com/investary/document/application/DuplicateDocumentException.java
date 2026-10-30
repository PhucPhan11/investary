package com.investary.document.application;

import java.util.UUID;

public class DuplicateDocumentException extends RuntimeException {

    private final UUID existingDocumentId;

    public DuplicateDocumentException(UUID existingDocumentId) {
        super("A document with identical content already exists");
        this.existingDocumentId = existingDocumentId;
    }

    public UUID getExistingDocumentId() {
        return existingDocumentId;
    }
}

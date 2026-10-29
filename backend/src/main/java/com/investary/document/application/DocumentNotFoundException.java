package com.investary.document.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class DocumentNotFoundException extends ApiException {

    public DocumentNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, ErrorCode.DOCUMENT_NOT_FOUND, "Document not found", "Document " + id + " was not found");
    }
}

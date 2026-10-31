package com.investary.document.application;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class InvalidDocumentException extends ApiException {

    public InvalidDocumentException(String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_DOCUMENT, "Invalid document", message);
    }
}

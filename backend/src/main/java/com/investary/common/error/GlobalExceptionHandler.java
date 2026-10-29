package com.investary.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.investary.document.application.DocumentNotFoundException;
import com.investary.document.application.DuplicateDocumentException;
import com.investary.document.application.InvalidDocumentException;
import com.investary.document.storage.StorageException;

/**
 * Maps all errors to RFC 9457 problem details. Framework errors (missing part, type mismatch,
 * upload too large, ...) are handled by {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail handleNotFound(DocumentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Document not found", ex.getMessage(), ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    ProblemDetail handleDuplicate(DuplicateDocumentException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Duplicate document", ex.getMessage(), ErrorCode.DUPLICATE_DOCUMENT);
        problem.setProperty("existingDocumentId", ex.getExistingDocumentId());
        return problem;
    }

    @ExceptionHandler(InvalidDocumentException.class)
    ProblemDetail handleInvalidDocument(InvalidDocumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid document", ex.getMessage(), ErrorCode.INVALID_DOCUMENT);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Unknown sort property '" + ex.getPropertyName() + "'", ErrorCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(StorageException.class)
    ProblemDetail handleStorage(StorageException ex) {
        log.error("Document storage failure", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Storage error",
                "The document content could not be processed", ErrorCode.STORAGE_ERROR);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred", ErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            String message = error.isBindingFailure() ? "has an invalid value" : error.getDefaultMessage();
            errors.putIfAbsent(error.getField(), message);
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid", ErrorCode.VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, ErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code.name());
        return problem;
    }
}

package com.investary.common.error;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Expected business failure that maps to a specific HTTP status and error code.
 * The message becomes the problem {@code detail}, so it must never contain internal details.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final String title;

    protected ApiException(HttpStatus status, ErrorCode code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    /**
     * Extra problem-detail properties, e.g. the id of an existing duplicate.
     */
    public Map<String, Object> getProperties() {
        return Map.of();
    }
}

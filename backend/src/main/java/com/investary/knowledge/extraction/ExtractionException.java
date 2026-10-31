package com.investary.knowledge.extraction;

/**
 * The source content cannot be read (invalid or password-protected PDF). Retrying will not help.
 */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}

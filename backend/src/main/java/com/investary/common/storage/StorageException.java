package com.investary.common.storage;

/**
 * Reading or writing stored content failed. Mapped to a generic 500 by {@code GlobalExceptionHandler};
 * messages must never contain storage keys or file paths.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.investary.knowledge.embedding;

/**
 * The embedding provider failed after its bounded retries. The indexing job may retry later.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}

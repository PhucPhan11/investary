package com.investary.knowledge.embedding;

/**
 * The embedding provider failed after its bounded retries. The indexing job may retry later, unless the
 * failure is {@linkplain #isPermanent() permanent} (the provider rejected the request itself).
 */
public class EmbeddingException extends RuntimeException {

    private final boolean permanent;

    public EmbeddingException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public EmbeddingException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}

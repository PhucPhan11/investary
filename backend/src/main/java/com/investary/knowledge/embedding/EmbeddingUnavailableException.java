package com.investary.knowledge.embedding;

/**
 * No embedding model is configured ({@code spring.ai.model.embedding=none}).
 */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException() {
        super("No embedding provider is configured (set INVESTARY_AI_EMBEDDING_PROVIDER)");
    }
}

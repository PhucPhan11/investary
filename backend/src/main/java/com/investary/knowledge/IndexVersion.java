package com.investary.knowledge;

import org.springframework.stereotype.Component;

/**
 * Fingerprint of everything that determines chunk text and vectors. Chunks store the version they
 * were built with; search only uses chunks of the active version, so changing the chunker or the
 * embedding model never mixes incompatible vectors (stale sources wait for an explicit reindex).
 */
@Component
public class IndexVersion {

    /** Bump when chunking or text extraction logic changes in a way that alters chunk text. */
    static final String PIPELINE = "p1";

    private final String value;
    private final KnowledgeProperties.Embedding embedding;

    public IndexVersion(KnowledgeProperties properties) {
        this.value = compute(properties);
        this.embedding = properties.embedding();
    }

    public static String compute(KnowledgeProperties properties) {
        KnowledgeProperties.Chunking chunking = properties.chunking();
        KnowledgeProperties.Embedding embedding = properties.embedding();
        return "%s-c%d-%d-%d-%s|%s|%d".formatted(PIPELINE, chunking.targetTokens(), chunking.maxTokens(),
                chunking.overlapTokens(), properties.email().bilingualMode(), embedding.modelId(), embedding.dimensions());
    }

    public String current() {
        return value;
    }

    public String embeddingModel() {
        return embedding.modelId();
    }

    public int dimensions() {
        return embedding.dimensions();
    }
}

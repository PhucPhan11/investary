package com.investary.knowledge.embedding;

/**
 * The model returned vectors of a different length than configured. This is a configuration error
 * (wrong model or dimension setting); retrying cannot fix it, so the job fails immediately.
 */
public class EmbeddingDimensionMismatchException extends RuntimeException {

    public EmbeddingDimensionMismatchException(String model, int expected, int actual) {
        super("Embedding model %s returned %d dimensions but investary.knowledge.embedding.dimensions is %d"
                .formatted(model, actual, expected));
    }
}

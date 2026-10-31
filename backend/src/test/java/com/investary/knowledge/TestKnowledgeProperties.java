package com.investary.knowledge;

import java.time.Duration;

/**
 * {@link KnowledgeProperties} with the application defaults, for unit tests.
 */
public final class TestKnowledgeProperties {

    private TestKnowledgeProperties() {
    }

    public static KnowledgeProperties defaults() {
        return withChunking(new KnowledgeProperties.Chunking(800, 900, 100));
    }

    public static KnowledgeProperties withChunking(KnowledgeProperties.Chunking chunking) {
        return new KnowledgeProperties(true,
                new KnowledgeProperties.Embedding("fake", "hashed-bag-of-words", FakeEmbeddingModel.DIMENSIONS, 16, 2, Duration.ofMillis(1)),
                chunking,
                new KnowledgeProperties.Email(KnowledgeProperties.BilingualMode.PRIMARY_ONLY),
                new KnowledgeProperties.Pdf(20, 0.8),
                new KnowledgeProperties.Worker(false, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(15), 3, Duration.ofMillis(1)),
                new KnowledgeProperties.Search(8, 20, 0.35, 3),
                new KnowledgeProperties.Assistant("fake:scripted", 12000, Duration.ofSeconds(5)));
    }
}

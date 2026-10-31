package com.investary.knowledge;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Research knowledge base settings ({@code investary.knowledge.*}). Chunking, bilingual mode and the
 * embedding model together form the index version: changing any of them makes existing chunks stale
 * (no longer searched) until they are reindexed.
 *
 * @param autoIndex track new documents and emails for indexing automatically
 */
@Validated
@ConfigurationProperties("investary.knowledge")
public record KnowledgeProperties(
        boolean autoIndex,
        @Valid @NotNull Embedding embedding,
        @Valid @NotNull Chunking chunking,
        @Valid @NotNull Email email,
        @Valid @NotNull Pdf pdf,
        @Valid @NotNull Worker worker,
        @Valid @NotNull Search search,
        @Valid @NotNull Assistant assistant) {

    /**
     * Describes the vectors produced by the configured {@code EmbeddingModel}.
     *
     * @param provider   provider name, recorded with every vector (e.g. {@code ollama})
     * @param model      model name, recorded with every vector (e.g. {@code bge-m3})
     * @param dimensions expected vector length; any other length fails the indexing job
     */
    public record Embedding(@NotBlank String provider, @NotBlank String model, @Positive int dimensions,
                            @Positive int batchSize, @PositiveOrZero int maxRetries, @NotNull Duration retryBackoff) {

        public String modelId() {
            return provider + ":" + model;
        }
    }

    /**
     * Token counts are estimates (cl100k tokenizer); chunks never split inside a line.
     */
    public record Chunking(@Positive int targetTokens, @Positive int maxTokens, @PositiveOrZero int overlapTokens) {
    }

    public record Email(@NotNull BilingualMode bilingualMode) {
    }

    public enum BilingualMode {
        /** Skip a translated copy (a long run of blocks in another language than the email starts with). */
        PRIMARY_ONLY,
        /** Index every language version. */
        ALL
    }

    /**
     * A page with fewer than {@code minCharsPerPage} characters counts as empty; when at least
     * {@code maxEmptyPageRatio} of the pages are empty the PDF is skipped as needing OCR.
     */
    public record Pdf(@Positive int minCharsPerPage, @DecimalMin("0.0") @DecimalMax("1.0") double maxEmptyPageRatio) {
    }

    public record Worker(boolean enabled, @Positive int concurrency, @NotNull Duration pollInterval,
                         @NotNull Duration reconcileInterval, @NotNull Duration lease, @Positive int maxAttempts,
                         @NotNull Duration retryBackoff) {
    }

    /**
     * @param minSimilarity cosine similarity a chunk needs unless it matches the question's ticker or terms
     */
    public record Search(@Positive int defaultResults, @Positive int maxResults,
                         @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity, @Positive int maxChunksPerSource) {
    }

    /**
     * @param modelLabel      chat provider and model, reported by the status endpoint
     * @param maxContextChars upper bound for source text sent to the chat model per question
     */
    public record Assistant(@NotBlank String modelLabel, @Positive int maxContextChars, @NotNull Duration timeout) {
    }
}

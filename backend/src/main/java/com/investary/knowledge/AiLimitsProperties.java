package com.investary.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Process-wide limits on concurrent AI provider calls (assistant, intelligence, indexing and automation).
 *
 * @param maxConcurrentChat      chat model calls running at the same time
 * @param maxConcurrentEmbedding embedding batches running at the same time
 */
@Validated
@ConfigurationProperties("investary.ai")
public record AiLimitsProperties(@Min(1) int maxConcurrentChat, @Min(1) int maxConcurrentEmbedding) {
}

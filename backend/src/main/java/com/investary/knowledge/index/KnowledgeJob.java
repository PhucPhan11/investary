package com.investary.knowledge.index;

import java.time.Instant;
import java.util.UUID;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * Index state of one source, which is also its indexing job.
 */
public record KnowledgeJob(UUID id, KnowledgeSourceType sourceType, UUID sourceId, KnowledgeSourceStatus status,
                           String statusReason, String indexVersion, int chunkCount, Integer pageCount, int attempts,
                           int maxAttempts, String lastError, Instant requestedAt, Instant startedAt,
                           Instant completedAt, Instant nextAttemptAt) {
}

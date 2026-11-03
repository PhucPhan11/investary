package com.investary.intelligence.application;

import java.time.LocalDate;
import java.util.UUID;

/** Published after a brief generation job failed permanently (no attempts left or not retryable). */
public record BriefJobFailedEvent(UUID jobId, UUID portfolioId, LocalDate businessDate, String errorCode) {
}

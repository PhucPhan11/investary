package com.investary.automation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Automated research processing (Phase 5 / Milestone 2).
 *
 * @param enabled                  start workflows automatically for new sources; manual processing always works
 * @param maxAttempts              attempts per job before it fails (waiting does not use attempts)
 * @param retryBackoff             first retry delay; doubles per attempt, with jitter
 * @param waitPoll                 how often a waiting job re-checks downloads or indexing
 * @param downloadWaitTimeout      longest wait for requested downloads before the stage is closed as partial
 * @param indexWaitTimeout         longest wait for indexing before the stage fails
 * @param maxDownloadsPerEmail     automatic PDF downloads requested for one email
 * @param maxDownloadsPerSyncRun   automatic PDF downloads requested for all emails of one Gmail sync run
 * @param autoAssess               create impact assessments automatically for directly matched holdings
 * @param maxAutoAssessmentsPerDay new automatic assessments per Asia/Ho_Chi_Minh day
 * @param assessMarketContext      also assess market-wide commentary for every portfolio (off by default)
 */
@Validated
@ConfigurationProperties("investary.workflow")
public record WorkflowProperties(
        boolean enabled,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff,
        @NotNull Duration waitPoll,
        @NotNull Duration downloadWaitTimeout,
        @NotNull Duration indexWaitTimeout,
        @Min(0) int maxDownloadsPerEmail,
        @Min(0) int maxDownloadsPerSyncRun,
        boolean autoAssess,
        @Min(0) int maxAutoAssessmentsPerDay,
        boolean assessMarketContext,
        @Valid @NotNull Worker worker) {

    /**
     * @param concurrency       jobs running at the same time
     * @param lease             a RUNNING job older than this is considered abandoned and re-queued
     * @param reconcileInterval how often new sources without a workflow are picked up
     */
    public record Worker(boolean enabled, @Min(1) int concurrency, @NotNull Duration pollInterval, @NotNull Duration lease,
                         @NotNull Duration reconcileInterval) {
    }
}

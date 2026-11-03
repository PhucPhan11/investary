package com.investary.automation.application.workflow;

import java.util.Set;

import com.investary.automation.domain.JobStatus;
import com.investary.automation.persistence.AutomationJobQueue.JobRow;

/**
 * Backend decision whether a job may be retried manually. Only FAILED jobs qualify; a job whose source was
 * deleted cannot succeed again, and each job has a bounded number of manual retries.
 */
final class RetryPolicy {

    static final String NOT_FAILED = "NOT_FAILED";
    static final String SOURCE_DELETED = "SOURCE_DELETED";
    static final String LIMIT_REACHED = "RETRY_LIMIT_REACHED";

    /** Failure codes that a retry cannot fix. */
    private static final Set<String> PERMANENT_CODES = Set.of("SOURCE_DELETED");

    private RetryPolicy() {
    }

    /**
     * @return null if a manual retry is allowed, otherwise the reason code
     */
    static String blockedReason(JobRow job, int manualRetries, int maxManualRetries) {
        if (job.status() != JobStatus.FAILED) {
            return NOT_FAILED;
        }
        if (job.errorCode() != null && PERMANENT_CODES.contains(job.errorCode())) {
            return SOURCE_DELETED;
        }
        if (manualRetries >= maxManualRetries) {
            return LIMIT_REACHED;
        }
        return null;
    }

    static String describe(String reason, JobRow job) {
        return switch (reason) {
            case SOURCE_DELETED -> "The source of this job was deleted; a retry cannot succeed";
            case LIMIT_REACHED -> "This job reached its manual retry limit";
            default -> "Only failed jobs can be retried; this job is " + job.status().name();
        };
    }
}

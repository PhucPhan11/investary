package com.investary.automation.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;
import com.investary.automation.persistence.AutomationJobQueue.JobRow;

class RetryPolicyTest {

    @Test
    void onlyFailedJobsWithinTheLimitAndAnExistingSourceCanBeRetried() {
        assertThat(RetryPolicy.blockedReason(job(JobStatus.FAILED, "INDEXING_FAILED"), 0, 5)).isNull();
        assertThat(RetryPolicy.blockedReason(job(JobStatus.RUNNING, null), 0, 5)).isEqualTo(RetryPolicy.NOT_FAILED);
        assertThat(RetryPolicy.blockedReason(job(JobStatus.SUCCEEDED, null), 0, 5)).isEqualTo(RetryPolicy.NOT_FAILED);
        assertThat(RetryPolicy.blockedReason(job(JobStatus.FAILED, "SOURCE_DELETED"), 0, 5)).isEqualTo(RetryPolicy.SOURCE_DELETED);
        assertThat(RetryPolicy.blockedReason(job(JobStatus.FAILED, "AI_TIMEOUT"), 5, 5)).isEqualTo(RetryPolicy.LIMIT_REACHED);
    }

    private static JobRow job(JobStatus status, String errorCode) {
        return new JobRow(UUID.randomUUID(), JobType.INDEX, UUID.randomUUID(), null, "k", status, null, 3, 3, Instant.EPOCH, null, null, null,
                errorCode, null, "w1", null, null, Instant.EPOCH, Instant.EPOCH);
    }
}

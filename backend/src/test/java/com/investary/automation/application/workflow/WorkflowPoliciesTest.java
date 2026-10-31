package com.investary.automation.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.dao.QueryTimeoutException;

import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.persistence.WorkflowStore.StageRow;
import com.investary.document.application.DocumentNotFoundException;
import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.AiExceptions.AiTimeoutException;
import com.investary.knowledge.embedding.EmbeddingException;
import com.investary.research.application.DownloadNotAllowedException;

class WorkflowPoliciesTest {

    @Test
    void mandatoryFailureFailsTheWorkflowAndOptionalFailureMakesItPartial() {
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.EMAIL, stages(WorkflowStage.KNOWLEDGE_INDEXED, StageStatus.FAILED)))
                .isEqualTo(StageStatus.FAILED);
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.EMAIL, stages(WorkflowStage.EMAIL_PARSED, StageStatus.FAILED)))
                .isEqualTo(StageStatus.FAILED);
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.EMAIL, stages(WorkflowStage.DOCUMENTS_PROCESSED, StageStatus.FAILED)))
                .isEqualTo(StageStatus.PARTIAL);
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.EMAIL, stages(WorkflowStage.IMPACT_ASSESSED, StageStatus.PARTIAL)))
                .isEqualTo(StageStatus.PARTIAL);
        // A library document has no email stages to require.
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.DOCUMENT, stages(WorkflowStage.EMAIL_PARSED, StageStatus.FAILED)))
                .isEqualTo(StageStatus.PARTIAL);
    }

    @Test
    void skippedStagesAreNotFailuresAndUnfinishedStagesKeepItRunning() {
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.EMAIL, stages(WorkflowStage.DOCUMENTS_PROCESSED, StageStatus.SKIPPED)))
                .isEqualTo(StageStatus.COMPLETED);
        assertThat(WorkflowStatusPolicy.finalStatus(WorkflowSourceType.EMAIL, stages(WorkflowStage.PORTFOLIO_MATCHED, StageStatus.RUNNING)))
                .isEqualTo(StageStatus.RUNNING);
    }

    @Test
    void failuresAreClassifiedAsTransientOrPermanent() {
        assertThat(FailureClassifier.classify(new AiTimeoutException(60)).retryable()).isTrue();
        assertThat(FailureClassifier.classify(new AiProviderException("down")).retryable()).isTrue();
        assertThat(FailureClassifier.classify(new AiProviderException("bad model", false)).retryable()).isFalse();
        assertThat(FailureClassifier.classify(new EmbeddingException("outage", null)).retryable()).isTrue();
        assertThat(FailureClassifier.classify(new EmbeddingException("rejected", new NonTransientAiException("x"), true)).retryable())
                .isFalse();
        assertThat(FailureClassifier.classify(new QueryTimeoutException("slow")).retryable()).isTrue();

        StageResult.Failed deleted = FailureClassifier.classify(new DocumentNotFoundException(java.util.UUID.randomUUID()));
        assertThat(deleted.code()).isEqualTo("SOURCE_DELETED");
        assertThat(deleted.retryable()).isFalse();
        StageResult.Failed blocked = FailureClassifier.classify(new DownloadNotAllowedException("blocked"));
        assertThat(blocked.retryable()).isFalse();
    }

    @Test
    void summariesNeverContainUrlsOrLineBreaksAndAreBounded() {
        String summary = FailureClassifier.safe("Failed https://example.com/r.pdf?token=secret\nat line 3 " + "x".repeat(400));
        assertThat(summary).doesNotContain("https://").doesNotContain("token=secret").doesNotContain("\n").contains("[url]");
        assertThat(summary.length()).isLessThanOrEqualTo(300);
        assertThat(FailureClassifier.safe(null)).isNull();
    }

    @Test
    void backoffGrowsExponentiallyWithBoundedJitter() {
        Duration base = Duration.ofMinutes(1);
        assertThat(FailureClassifier.backoff(base, 1, 0.5)).isEqualTo(Duration.ofMinutes(1));
        assertThat(FailureClassifier.backoff(base, 3, 0.5)).isEqualTo(Duration.ofMinutes(4));
        assertThat(FailureClassifier.backoff(base, 2, 0.0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(FailureClassifier.backoff(base, 2, 0.999)).isLessThan(Duration.ofMinutes(3));
        assertThat(FailureClassifier.backoff(base, 50, 0.5)).isEqualTo(Duration.ofMinutes(1024));
    }

    private static List<StageRow> stages(WorkflowStage changed, StageStatus status) {
        List<StageRow> rows = new ArrayList<>();
        for (WorkflowStage stage : WorkflowStage.values()) {
            StageStatus value = stage == changed ? status : StageStatus.COMPLETED;
            rows.add(new StageRow(stage, stage == WorkflowStage.COMPLETED ? StageStatus.PENDING : value, null, "{}", null, null, null));
        }
        return rows;
    }
}

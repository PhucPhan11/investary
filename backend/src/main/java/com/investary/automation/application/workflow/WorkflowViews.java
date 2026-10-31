package com.investary.automation.application.workflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.persistence.AutomationJobQueue.JobRow;
import com.investary.automation.persistence.WorkflowStore.MatchRow;
import com.investary.automation.persistence.WorkflowStore.StageRow;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API views of workflows and jobs. They carry codes, counters and safe summaries only: no storage keys,
 * URLs, credentials or stack traces.
 */
public final class WorkflowViews {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WorkflowViews() {
    }

    /**
     * @param status            workflow status, or {@code NOT_STARTED}
     * @param importedBeforeAutomation true for a source without workflow that predates the feature
     */
    public record WorkflowView(UUID id, WorkflowSourceType sourceType, UUID sourceId, String status, String trigger,
                               String processingVersion, Instant startedAt, Instant completedAt, Instant updatedAt,
                               boolean importedBeforeAutomation, List<StageView> stages, List<MatchView> matches, List<JobView> jobs) {

        static WorkflowView notStarted(WorkflowSourceType type, UUID sourceId, boolean importedBeforeAutomation) {
            return new WorkflowView(null, type, sourceId, "NOT_STARTED", null, null, null, null, null, importedBeforeAutomation,
                    List.of(), List.of(), List.of());
        }
    }

    public record StageView(WorkflowStage stage, String status, String reason, JsonNode counts, Instant startedAt,
                            Instant completedAt) {
    }

    public record MatchView(UUID portfolioId, UUID instrumentId, String symbol, String matchKind, LocalDate publishedOn,
                            Instant matchedAt) {
    }

    public record JobView(UUID id, JobType type, UUID workflowId, WorkflowSourceType sourceType, UUID sourceId, UUID portfolioId,
                          JobStatus status, String outcome, int attempts, int maxAttempts, Instant nextAttemptAt, Instant startedAt,
                          Instant completedAt, String errorCode, String errorSummary, String processingVersion, Integer inputTokens,
                          Integer outputTokens, Instant createdAt, Instant updatedAt) {
    }

    static WorkflowView workflow(WorkflowRow row, List<StageRow> stages, List<MatchRow> matches, List<JobRow> jobs) {
        return new WorkflowView(row.id(), row.sourceType(), row.sourceId(), row.status().name(), row.trigger().name(),
                row.processingVersion(), row.startedAt(), row.completedAt(), row.updatedAt(), false,
                stages.stream().map(s -> new StageView(s.stage(), s.status().name(), s.reason(), counts(s.counts()), s.startedAt(),
                        s.completedAt())).toList(),
                matches.stream().map(m -> new MatchView(m.portfolioId(), m.instrumentId(), m.symbol(), m.matchKind(), m.publishedOn(),
                        m.matchedAt())).toList(),
                jobs.stream().map(j -> job(j, row)).toList());
    }

    static JobView job(JobRow job, WorkflowRow workflow) {
        return new JobView(job.id(), job.type(), job.workflowId(), workflow == null ? null : workflow.sourceType(),
                workflow == null ? null : workflow.sourceId(), job.portfolioId(), job.status(), job.outcome(), job.attempts(),
                job.maxAttempts(), job.nextAttemptAt(), job.startedAt(), job.completedAt(), job.errorCode(), job.errorSummary(),
                job.processingVersion(), job.inputTokens(), job.outputTokens(), job.createdAt(), job.updatedAt());
    }

    private static JsonNode counts(String text) {
        return text == null ? JSON.createObjectNode() : JSON.readTree(text);
    }
}

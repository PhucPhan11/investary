package com.investary.automation.application.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.automation.WorkflowProperties;
import com.investary.automation.application.workflow.StageResult.Done;
import com.investary.automation.application.workflow.StageResult.Failed;
import com.investary.automation.application.workflow.StageResult.NextJob;
import com.investary.automation.application.workflow.StageResult.StageUpdate;
import com.investary.automation.application.workflow.StageResult.Wait;
import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.AutomationJobQueue.JobRow;
import com.investary.automation.persistence.WorkflowStore;
import com.investary.automation.persistence.WorkflowStore.StageRow;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one claimed job: the stage handler first (no transaction open, so HTTP and model calls never hold
 * one), then the result in one short transaction. Every write is guarded by the claim token; if the claim
 * was lost (lease expired, source deleted) the result is dropped.
 */
@Component
public class WorkflowJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowJobProcessor.class);

    private final Map<JobType, StageHandler> handlers = new EnumMap<>(JobType.class);
    private final AutomationJobQueue queue;
    private final WorkflowStore store;
    private final WorkflowProperties properties;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;

    public WorkflowJobProcessor(List<StageHandler> handlers, AutomationJobQueue queue, WorkflowStore store, WorkflowProperties properties,
                                TransactionTemplate transactions, JsonMapper json, Clock clock) {
        handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
        this.queue = queue;
        this.store = store;
        this.properties = properties;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    public void process(ClaimedJob job) {
        WorkflowRow workflow = store.find(job.workflowId()).orElse(null);
        if (workflow == null || !queue.isClaimed(job)) {
            // The source was deleted (the workflow and its jobs went with it), or the claim expired.
            return;
        }
        if (!job.resumed() && job.type() != JobType.ASSESS) {
            store.updateStage(workflow.id(), job.type().stage(), StageStatus.RUNNING, null, null);
        }
        StageResult result;
        try {
            result = handlers.get(job.type()).run(job, workflow);
        } catch (RuntimeException e) {
            result = FailureClassifier.classify(e);
            if ("INTERNAL_ERROR".equals(((Failed) result).code())) {
                log.error("Workflow job failed unexpectedly jobId={} type={} workflowId={}", job.id(), job.type(), workflow.id(), e);
            }
        }
        switch (result) {
            case Done done -> applyDone(job, workflow, done);
            case Wait wait -> applyWait(job, wait);
            case Failed failed -> applyFailure(job, workflow, failed);
        }
    }

    private void applyDone(ClaimedJob job, WorkflowRow workflow, Done done) {
        boolean applied = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!queue.complete(job, done.reason(), done.inputTokens(), done.outputTokens())) {
                return false;
            }
            for (StageUpdate update : done.otherStages()) {
                store.updateStage(workflow.id(), update.stage(), update.status(), update.reason(), counts(update.counts()));
            }
            if (job.type() == JobType.ASSESS) {
                aggregateAssessments(workflow.id());
            } else {
                store.updateStage(workflow.id(), job.type().stage(), done.status(), done.reason(), counts(done.counts()));
            }
            for (NextJob next : done.next()) {
                enqueue(workflow, next);
            }
            finishIfIdle(workflow.id());
            return true;
        }));
        if (applied) {
            log.info("Workflow stage done workflowId={} jobId={} type={} status={} reason={}", workflow.id(), job.id(), job.type(),
                    done.status(), done.reason());
        } else {
            log.warn("Workflow job finished after its claim was lost jobId={}; result discarded", job.id());
        }
    }

    private void applyWait(ClaimedJob job, Wait wait) {
        Instant now = clock.instant();
        boolean fresh = job.waitDeadline() == null || wait.restartDeadline();
        Instant deadline = fresh ? now.plus(wait.timeout()) : job.waitDeadline();
        queue.waitUntil(job, now.plus(wait.delay()), deadline);
    }

    private void applyFailure(ClaimedJob job, WorkflowRow workflow, Failed failed) {
        boolean retry = failed.retryable() && job.attempts() < job.maxAttempts();
        String summary = failed.summary() == null ? failed.code() : failed.summary();
        if (retry) {
            Instant next = clock.instant().plus(FailureClassifier.backoff(properties.retryBackoff(), job.attempts(),
                    ThreadLocalRandom.current().nextDouble()));
            queue.retryLater(job, failed.code(), summary, next);
        } else {
            transactions.executeWithoutResult(status -> {
                if (!queue.fail(job, failed.code(), summary)) {
                    return;
                }
                if (job.type() == JobType.ASSESS) {
                    aggregateAssessments(workflow.id());
                } else {
                    store.updateStage(workflow.id(), job.type().stage(), StageStatus.FAILED, failed.code(), null);
                }
                finishIfIdle(workflow.id());
            });
        }
        log.warn("Workflow job failed workflowId={} jobId={} type={} attempt={}/{} retry={} code={}", workflow.id(), job.id(), job.type(),
                job.attempts(), job.maxAttempts(), retry, failed.code());
    }

    void enqueue(WorkflowRow workflow, NextJob next) {
        String key = workflow.id() + ":" + next.type() + (next.portfolioId() == null ? "" : ":" + next.portfolioId())
                + (next.keyPart() == null ? "" : ":" + next.keyPart()) + ":" + WorkflowService.VERSION;
        queue.enqueue(workflow.id(), next.type(), next.portfolioId(), key, properties.maxAttempts(), WorkflowService.VERSION);
        if (next.type() != JobType.ASSESS) {
            store.updateStage(workflow.id(), next.type().stage(), StageStatus.PENDING, null, null);
        } else {
            store.updateStage(workflow.id(), WorkflowStage.IMPACT_ASSESSED, StageStatus.PENDING, null, null);
        }
    }

    /**
     * IMPACT_ASSESSED summarises all assessment jobs of the workflow: completed when each created (or found)
     * its assessment, partial when some failed, failed when all failed, skipped when none could run.
     */
    private void aggregateAssessments(UUID workflowId) {
        List<JobRow> jobs = queue.forWorkflow(workflowId).stream().filter(j -> j.type() == JobType.ASSESS).toList();
        if (jobs.isEmpty()) {
            return;
        }
        if (jobs.stream().anyMatch(j -> j.status().isActive())) {
            store.updateStage(workflowId, WorkflowStage.IMPACT_ASSESSED, StageStatus.RUNNING, null, null);
            return;
        }
        long failed = jobs.stream().filter(j -> j.status() == JobStatus.FAILED).count();
        long created = jobs.stream().filter(j -> "CREATED".equals(j.outcome())).count();
        long existing = jobs.stream().filter(j -> "ALREADY_ASSESSED".equals(j.outcome())).count();
        long skipped = jobs.size() - failed - created - existing;
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("portfolios", jobs.size());
        counts.put("created", created);
        counts.put("alreadyAssessed", existing);
        counts.put("skipped", skipped);
        counts.put("failed", failed);
        StageStatus status;
        String reason = null;
        if (failed == jobs.size()) {
            status = StageStatus.FAILED;
            reason = "ASSESSMENTS_FAILED";
        } else if (failed > 0) {
            status = StageStatus.PARTIAL;
            reason = "SOME_ASSESSMENTS_FAILED";
        } else if (created + existing == 0) {
            status = StageStatus.SKIPPED;
            reason = jobs.stream().map(JobRow::outcome).filter(o -> o != null).findFirst().orElse(null);
        } else {
            status = StageStatus.COMPLETED;
        }
        store.updateStage(workflowId, WorkflowStage.IMPACT_ASSESSED, status, reason, counts(counts));
    }

    /**
     * Sets the workflow status once no job is active: stages the chain never reached are SKIPPED, then the
     * status follows {@link WorkflowStatusPolicy}. Runs under a row lock on the workflow.
     */
    void finishIfIdle(UUID workflowId) {
        WorkflowRow workflow = store.lock(workflowId).orElse(null);
        if (workflow == null) {
            return;
        }
        if (queue.hasActiveJobs(workflowId)) {
            if (workflow.status() != StageStatus.RUNNING) {
                store.updateStatus(workflowId, StageStatus.RUNNING, null);
                store.updateStage(workflowId, WorkflowStage.COMPLETED, StageStatus.PENDING, null, null);
            }
            return;
        }
        List<StageRow> stages = store.stages(workflowId);
        for (StageRow stage : stages) {
            if (stage.stage() != WorkflowStage.COMPLETED && !stage.status().isTerminal()) {
                store.updateStage(workflowId, stage.stage(), StageStatus.SKIPPED, "NOT_REACHED", null);
            }
        }
        StageStatus status = WorkflowStatusPolicy.finalStatus(workflow.sourceType(), store.stages(workflowId));
        store.updateStage(workflowId, WorkflowStage.COMPLETED, status, null, null);
        store.updateStatus(workflowId, status, clock.instant());
        log.info("Workflow finished workflowId={} status={}", workflowId, status);
    }

    private String counts(Map<String, Object> counts) {
        return counts == null || counts.isEmpty() ? null : json.writeValueAsString(counts);
    }
}

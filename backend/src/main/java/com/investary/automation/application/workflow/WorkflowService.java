package com.investary.automation.application.workflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.automation.WorkflowProperties;
import com.investary.automation.application.AutomationExceptions.AutomationJobNotFoundException;
import com.investary.automation.application.AutomationExceptions.JobNotRetryableException;
import com.investary.automation.application.AutomationExceptions.WorkflowNotFoundException;
import com.investary.automation.application.workflow.StageResult.NextJob;
import com.investary.automation.application.workflow.WorkflowViews.JobView;
import com.investary.automation.application.workflow.WorkflowViews.WorkflowView;
import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.domain.WorkflowTrigger;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationJobQueue.JobRow;
import com.investary.automation.persistence.WorkflowStore;
import com.investary.automation.persistence.WorkflowStore.GmailSource;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.common.error.ApiException;
import com.investary.common.web.PageResponse;
import com.investary.document.application.DocumentUploadedEvent;
import com.investary.document.application.UploadOrigin;
import com.investary.email.application.EmailImportedEvent;
import com.investary.email.domain.SourceType;
import com.investary.research.application.ReportDownloadedEvent;

/**
 * Starts, re-opens and reports research workflows (Phase 5 / Milestone 2).
 * <p>
 * Triggers arrive as events that the owning modules publish only after their data is committed: a new
 * email (Gmail or EML upload), a library upload, or a downloaded report. Starting is idempotent (one
 * workflow per source), and a periodic reconcile catches sources whose event was lost (crash between
 * the commit and the event). Sources imported before the feature was installed are processed only when
 * the user asks ({@link #process(UUID)}).
 */
@Service
public class WorkflowService {

    /** Processing version recorded on workflows and part of every job's idempotency key. */
    public static final String VERSION = "w1";

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    private static final Duration RECONCILE_SETTLE = Duration.ofMinutes(2);
    private static final int RECONCILE_BATCH = 50;

    private final WorkflowStore store;
    private final AutomationJobQueue queue;
    private final WorkflowJobProcessor processor;
    private final WorkflowSources sources;
    private final WorkflowProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WorkflowService(WorkflowStore store, AutomationJobQueue queue, WorkflowJobProcessor processor, WorkflowSources sources,
                           WorkflowProperties properties, TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.queue = queue;
        this.processor = processor;
        this.sources = sources;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @EventListener
    public void onEmailImported(EmailImportedEvent event) {
        if (properties.enabled()) {
            safely(() -> start(WorkflowSourceType.EMAIL, event.emailId(),
                    event.sourceType() == SourceType.GMAIL ? WorkflowTrigger.GMAIL : WorkflowTrigger.EMAIL_IMPORT), event.emailId());
        }
    }

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        if (properties.enabled() && event.origin() == UploadOrigin.LIBRARY) {
            safely(() -> start(WorkflowSourceType.DOCUMENT, event.documentId(), WorkflowTrigger.DOCUMENT_UPLOAD), event.documentId());
        }
    }

    /**
     * A report became available for an email (manual or automatic download): indexing, matching and
     * assessment run again for the email's workflow. Emails without a workflow (imported before the
     * feature) are left alone. A workflow still waiting for its own downloads picks the report up itself.
     */
    @EventListener
    public void onReportDownloaded(ReportDownloadedEvent event) {
        safely(() -> transactions.executeWithoutResult(status -> store.findBySource(event.emailId()).ifPresent(workflow -> {
            boolean downloadsActive = queue.forWorkflow(workflow.id()).stream()
                    .anyMatch(job -> job.type() == JobType.DOWNLOADS && job.status().isActive());
            if (!downloadsActive) {
                processor.enqueue(workflow, NextJob.of(JobType.INDEX));
                processor.finishIfIdle(workflow.id());
                log.info("Workflow re-opened for a downloaded report workflowId={}", workflow.id());
            }
        })), event.emailId());
    }

    /**
     * Creates the workflow and its first job, unless the source already has one.
     *
     * @return the workflow id (new or existing)
     */
    public UUID start(WorkflowSourceType type, UUID sourceId, WorkflowTrigger trigger) {
        Optional<GmailSource> gmail = type == WorkflowSourceType.EMAIL ? store.gmailSource(sourceId) : Optional.empty();
        UUID id = transactions.execute(status -> {
            Optional<UUID> created = store.create(type, sourceId, trigger, VERSION, gmail.map(GmailSource::syncRunId).orElse(null),
                    clock.instant());
            if (created.isEmpty()) {
                return store.findBySource(sourceId).map(WorkflowRow::id).orElseThrow();
            }
            WorkflowRow workflow = store.find(created.get()).orElseThrow();
            processor.enqueue(workflow, NextJob.of(JobType.PREPARE));
            log.info("Workflow started workflowId={} sourceType={} trigger={}", workflow.id(), type, trigger);
            return workflow.id();
        });
        return id;
    }

    /**
     * Manual processing: starts a workflow for a source that has none (e.g. imported before the feature),
     * retries the failed jobs of an existing one, or runs a finished one again. Repeated work is cheap
     * and idempotent: completed downloads and current indexes are reused, existing assessments are kept.
     *
     * @throws WorkflowNotFoundException if the id is neither an email nor a document
     */
    public WorkflowView process(UUID sourceId) {
        Optional<WorkflowRow> existing = store.findBySource(sourceId);
        if (existing.isEmpty()) {
            start(source(sourceId).type(), sourceId, WorkflowTrigger.MANUAL);
            return view(sourceId);
        }
        WorkflowRow workflow = existing.get();
        transactions.executeWithoutResult(status -> {
            List<JobRow> jobs = queue.forWorkflow(workflow.id());
            if (jobs.stream().anyMatch(job -> job.status().isActive())) {
                return;
            }
            List<JobRow> failed = jobs.stream().filter(job -> job.status() == JobStatus.FAILED).toList();
            if (failed.isEmpty()) {
                processor.enqueue(workflow, NextJob.of(JobType.PREPARE));
            } else {
                failed.forEach(job -> reset(workflow.id(), job));
            }
            processor.finishIfIdle(workflow.id());
        });
        log.info("Workflow processing requested workflowId={}", workflow.id());
        return view(sourceId);
    }

    /**
     * Manual retry of one failed job (same job row; attempts reset). Earlier stages are not repeated.
     *
     * @throws JobNotRetryableException if the job is not FAILED
     */
    public JobView retry(UUID jobId) {
        JobRow job = queue.find(jobId).orElseThrow(() -> new AutomationJobNotFoundException(jobId));
        transactions.executeWithoutResult(status -> {
            if (!reset(job.workflowId(), job)) {
                throw new JobNotRetryableException(job.status().name());
            }
            processor.finishIfIdle(job.workflowId());
        });
        log.info("Workflow job retry requested jobId={} type={}", jobId, job.type());
        return job(jobId);
    }

    private boolean reset(UUID workflowId, JobRow job) {
        if (!queue.resetFailed(job.id())) {
            return false;
        }
        WorkflowStage stage = job.type().stage();
        store.updateStage(workflowId, stage, StageStatus.PENDING, null, null);
        return true;
    }

    /**
     * Starts workflows for sources imported since activation whose event was missed.
     *
     * @return number of workflows started
     */
    public int reconcile() {
        if (!properties.enabled()) {
            return 0;
        }
        var settled = clock.instant().minus(RECONCILE_SETTLE);
        int started = 0;
        for (UUID emailId : store.emailsWithoutWorkflow(settled, RECONCILE_BATCH)) {
            start(WorkflowSourceType.EMAIL, emailId, WorkflowTrigger.RECONCILE);
            started++;
        }
        for (UUID documentId : store.libraryDocumentsWithoutWorkflow(settled.minus(RECONCILE_SETTLE), RECONCILE_BATCH)) {
            start(WorkflowSourceType.DOCUMENT, documentId, WorkflowTrigger.RECONCILE);
            started++;
        }
        return started;
    }

    /**
     * @return the workflow of a source, or a NOT_STARTED view for a known source without one
     * @throws WorkflowNotFoundException if the id is neither an email nor a document
     */
    public WorkflowView view(UUID sourceId) {
        Optional<WorkflowRow> workflow = store.findBySource(sourceId);
        if (workflow.isEmpty()) {
            SourceRef source = source(sourceId);
            return WorkflowView.notStarted(source.type(), sourceId, source.createdAt().isBefore(store.activatedAt()));
        }
        WorkflowRow row = workflow.get();
        return WorkflowViews.workflow(row, store.stages(row.id()), store.matches(row.id()), queue.forWorkflow(row.id()));
    }

    public JobView job(UUID id) {
        return queue.find(id).map(this::jobView).orElseThrow(() -> new AutomationJobNotFoundException(id));
    }

    public PageResponse<JobView> jobs(JobStatus status, JobType type, UUID workflowId, int page, int size) {
        long total = queue.count(status, type, workflowId);
        List<JobView> content = queue.list(status, type, workflowId, page, size).stream().map(this::jobView).toList();
        return new PageResponse<>(content, page, size, total, size == 0 ? 0 : (int) ((total + size - 1) / size));
    }

    private JobView jobView(JobRow job) {
        WorkflowRow workflow = store.find(job.workflowId()).orElse(null);
        return WorkflowViews.job(job, workflow);
    }

    private SourceRef source(UUID sourceId) {
        try {
            return new SourceRef(WorkflowSourceType.EMAIL, sources.email(sourceId).importedAt());
        } catch (ApiException notEmail) {
            try {
                return new SourceRef(WorkflowSourceType.DOCUMENT, sources.document(sourceId).getUploadedAt());
            } catch (ApiException notDocument) {
                throw new WorkflowNotFoundException(sourceId);
            }
        }
    }

    private record SourceRef(WorkflowSourceType type, Instant createdAt) {
    }

    private static void safely(Runnable action, UUID sourceId) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Never fail the import that published the event; the reconcile picks the source up later.
            log.error("Could not start or re-open the workflow sourceId={}", sourceId, e);
        }
    }
}

package com.investary.automation.application.center;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.investary.automation.WorkflowProperties;
import com.investary.automation.application.GmailSyncService;
import com.investary.automation.application.GmailSyncService.GmailStatusView;
import com.investary.automation.application.center.SystemHealth.ComponentHealth;
import com.investary.automation.application.center.SystemHealth.GmailFacts;
import com.investary.automation.application.center.SystemHealth.WorkerFacts;
import com.investary.automation.application.settings.AutomationSettings;
import com.investary.automation.application.workflow.AutomationJobWorker;
import com.investary.automation.domain.JobStatus;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationStatsStore;
import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;
import com.investary.intelligence.application.BriefJobWorker;
import com.investary.intelligence.application.BriefProperties;
import com.investary.intelligence.application.BriefService;
import com.investary.intelligence.application.BriefService.BriefStats;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.embedding.EmbeddingService;
import com.investary.knowledge.index.KnowledgeIndexWorker;
import com.investary.research.download.DownloadJobWorker;
import com.investary.research.download.DownloadProperties;

/**
 * Automation Center overview: Gmail status, record counts for a labelled window, and component health. Every
 * figure is read from the database or the running workers at request time.
 */
@Service
public class AutomationOverviewService {

    private static final Map<String, Duration> PERIODS = Map.of("24h", Duration.ofHours(24), "7d", Duration.ofDays(7), "30d",
            Duration.ofDays(30));
    private static final Duration RECENT_FAILURES = Duration.ofHours(24);

    private final GmailSyncService gmail;
    private final AutomationStatsStore stats;
    private final AutomationJobQueue jobs;
    private final BriefService briefs;
    private final AutomationSettings settings;
    private final WorkflowProperties workflow;
    private final DownloadProperties downloads;
    private final KnowledgeProperties knowledge;
    private final BriefProperties brief;
    private final EmbeddingService embeddings;
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<AutomationJobWorker> workflowWorker;
    private final ObjectProvider<DownloadJobWorker> downloadWorker;
    private final ObjectProvider<KnowledgeIndexWorker> indexWorker;
    private final ObjectProvider<BriefJobWorker> briefWorker;
    private final Clock clock;

    public AutomationOverviewService(GmailSyncService gmail, AutomationStatsStore stats, AutomationJobQueue jobs, BriefService briefs,
                                     AutomationSettings settings, WorkflowProperties workflow, DownloadProperties downloads,
                                     KnowledgeProperties knowledge, BriefProperties brief, EmbeddingService embeddings,
                                     ObjectProvider<ChatModel> chatModels, ObjectProvider<AutomationJobWorker> workflowWorker,
                                     ObjectProvider<DownloadJobWorker> downloadWorker, ObjectProvider<KnowledgeIndexWorker> indexWorker,
                                     ObjectProvider<BriefJobWorker> briefWorker, Clock clock) {
        this.gmail = gmail;
        this.stats = stats;
        this.jobs = jobs;
        this.briefs = briefs;
        this.settings = settings;
        this.workflow = workflow;
        this.downloads = downloads;
        this.knowledge = knowledge;
        this.brief = brief;
        this.embeddings = embeddings;
        this.chatModels = chatModels;
        this.workflowWorker = workflowWorker;
        this.downloadWorker = downloadWorker;
        this.indexWorker = indexWorker;
        this.briefWorker = briefWorker;
        this.clock = clock;
    }

    /**
     * @param period {@code 24h}, {@code 7d} or {@code 30d}
     */
    public OverviewView overview(String period) {
        Duration window = PERIODS.get(period);
        if (window == null) {
            throw new InvalidPeriodException(period);
        }
        Instant now = clock.instant();
        Instant from = now.minus(window);
        Instant recent = now.minus(RECENT_FAILURES);
        GmailStatusView status = gmail.status();
        BriefStats briefStats = briefs.stats(from, now);
        BriefStats recentBriefs = briefs.stats(recent, now);

        List<JobStatus> active = List.of(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.WAITING);
        Metrics metrics = new Metrics(stats.emailsImported(from, now), stats.emailsProcessed(from, now), stats.reportsDownloaded(from, now),
                stats.documentsIndexed(from, now), stats.assessmentsCreated(from, now),
                jobs.countFailed(from, now) + briefStats.failedJobs(), jobs.countInStatus(active, null) + briefStats.activeJobs(),
                briefStats.briefsGenerated());

        GmailFacts gmailFacts = new GmailFacts(status.configured(), status.account() == null ? null : status.account().status(),
                status.schedulerEnabled(), status.lastRun() != null && "FAILED".equals(status.lastRun().status()),
                status.account() == null ? null : status.account().lastSyncSuccessAt(), settings.gmailSync().interval());
        boolean chat = chatModels.getIfAvailable() != null;
        List<ComponentHealth> health = List.of(
                SystemHealth.gmail(gmailFacts, now),
                SystemHealth.worker("RESEARCH_INGESTION", new WorkerFacts(workflow.enabled(),
                        "Automatic workflows are off (INVESTARY_WORKFLOW_ENABLED)", workflowWorker.getIfAvailable() != null,
                        workflowWorker.getIfAvailable() == null ? null : workflowWorker.getIfAvailable().lastPollAt(),
                        workflow.worker().pollInterval(), jobs.countFailed(recent, now),
                        jobs.countInStatus(active, now.minus(settings.stuckJobAfter())), jobs.lastCompletedAt()), now),
                SystemHealth.worker("DOCUMENT_PROCESSING", new WorkerFacts(true, null, downloadWorker.getIfAvailable() != null,
                        downloadWorker.getIfAvailable() == null ? null : downloadWorker.getIfAvailable().lastPollAt(),
                        downloads.worker().pollInterval(), stats.downloadsFailed(recent, now), 0, stats.lastDownloadAt()), now),
                SystemHealth.worker("KNOWLEDGE_INDEXING", new WorkerFacts(embeddings.isAvailable(), "No embedding model is configured",
                        indexWorker.getIfAvailable() != null,
                        indexWorker.getIfAvailable() == null ? null : indexWorker.getIfAvailable().lastPollAt(),
                        knowledge.worker().pollInterval(), stats.indexFailed(recent, now),
                        stats.indexPendingSince(now.minus(settings.stuckJobAfter())), stats.lastIndexedAt()), now),
                briefHealth(recentBriefs, chat, now));
        return new OverviewView(period, from, now, status, metrics, health);
    }

    private ComponentHealth briefHealth(BriefStats recent, boolean chat, Instant now) {
        ComponentHealth health = SystemHealth.worker("BRIEF_GENERATION", new WorkerFacts(recent.enabled(), "Daily briefs are switched off",
                briefWorker.getIfAvailable() != null, briefWorker.getIfAvailable() == null ? null : briefWorker.getIfAvailable().lastPollAt(),
                brief.worker().pollInterval(), recent.failedJobs(), 0, recent.lastBriefAt()), now);
        if (recent.lastJobFailed()) {
            health = SystemHealth.degrade(health, "The last brief generation failed");
        }
        if (!chat) {
            health = SystemHealth.degrade(health, "No chat model is configured; briefs are deterministic only");
        }
        return health;
    }

    /**
     * @param emailsProcessed email workflows that finished (completed or partial) in the window
     * @param failedJobs      research-processing and brief jobs that failed in the window
     * @param pendingJobs     jobs queued or running now (not limited to the window)
     */
    public record Metrics(long emailsImported, long emailsProcessed, long reportsDownloaded, long documentsIndexed, long assessmentsCreated,
                          long failedJobs, long pendingJobs, long briefsGenerated) {
    }

    /**
     * @param from start of the metrics window
     * @param to   end of the window (the time of the request)
     */
    public record OverviewView(String period, Instant from, Instant to, GmailStatusView gmail, Metrics metrics,
                               List<ComponentHealth> health) {
    }

    static class InvalidPeriodException extends ApiException {
        InvalidPeriodException(String period) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Invalid period", "Period must be 24h, 7d or 30d");
        }
    }
}

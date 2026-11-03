package com.investary.automation.application.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.investary.automation.application.workflow.WorkflowSources;
import com.investary.automation.domain.GmailAccountStatus;
import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.NotificationEntity;
import com.investary.automation.domain.SyncRunStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationJobQueue.JobRow;
import com.investary.automation.persistence.GmailAccountStore;
import com.investary.automation.persistence.GmailSyncRunStore;
import com.investary.automation.persistence.GmailSyncRunStore.RunRow;
import com.investary.automation.persistence.WorkflowStore;
import com.investary.automation.persistence.WorkflowStore.MatchRow;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.intelligence.application.BriefGeneratedEvent;
import com.investary.intelligence.application.BriefJobFailedEvent;
import com.investary.intelligence.application.ImpactAssessmentCreatedEvent;
import com.investary.intelligence.application.SourceRelevanceService;

/**
 * Collects the stored facts a notification rule needs and hands the result to {@link NotificationService}.
 * Called after the triggering change is committed: by Gmail sync when a run ends, by the workflow processor
 * after matching and when a workflow fails, and for intelligence events (published after commit). Every entry
 * point swallows its own failures, so notifications can never break the work that triggered them.
 */
@Component
public class NotificationTriggers {

    private static final Logger log = LoggerFactory.getLogger(NotificationTriggers.class);

    private final NotificationService notifications;
    private final WorkflowStore workflows;
    private final AutomationJobQueue jobs;
    private final WorkflowSources sources;
    private final GmailSyncRunStore runs;
    private final GmailAccountStore accounts;
    private final SourceRelevanceService relevance;

    public NotificationTriggers(NotificationService notifications, WorkflowStore workflows, AutomationJobQueue jobs, WorkflowSources sources,
                                GmailSyncRunStore runs, GmailAccountStore accounts, SourceRelevanceService relevance) {
        this.notifications = notifications;
        this.workflows = workflows;
        this.jobs = jobs;
        this.sources = sources;
        this.runs = runs;
        this.accounts = accounts;
        this.relevance = relevance;
    }

    /** After the MATCH stage of a workflow was applied: one notification per portfolio with direct matches. */
    public void matched(UUID workflowId) {
        safely("matched", () -> {
            WorkflowRow workflow = workflows.find(workflowId).orElse(null);
            if (workflow == null) {
                return;
            }
            Map<UUID, List<String>> symbols = new LinkedHashMap<>();
            for (MatchRow match : workflows.matches(workflowId)) {
                if ("DIRECT".equals(match.matchKind()) && match.symbol() != null) {
                    List<String> list = symbols.computeIfAbsent(match.portfolioId(), p -> new ArrayList<>());
                    if (!list.contains(match.symbol())) {
                        list.add(match.symbol());
                    }
                }
            }
            if (symbols.isEmpty()) {
                return;
            }
            Map<UUID, String> names = relevance.portfolioNames();
            String title = sources.title(workflow.sourceType(), workflow.sourceId());
            NotificationEntity entity = workflow.sourceType() == WorkflowSourceType.EMAIL ? NotificationEntity.EMAIL : NotificationEntity.DOCUMENT;
            symbols.forEach((portfolioId, held) -> notifications.notify(NotificationRules.portfolioResearch(entity, workflow.sourceId(), title,
                    portfolioId, names.get(portfolioId), held)));
        });
    }

    /** After a workflow ended FAILED. */
    public void workflowFailed(UUID workflowId) {
        safely("workflowFailed", () -> {
            WorkflowRow workflow = workflows.find(workflowId).orElse(null);
            if (workflow == null) {
                return;
            }
            JobRow failed = jobs.forWorkflow(workflowId).stream().filter(job -> job.status() == JobStatus.FAILED)
                    .reduce((first, second) -> second).orElse(null);
            if (failed == null) {
                return;
            }
            notifications.notify(NotificationRules.workflowFailed(workflowId, failed.id(), failed.type().name(), failed.errorCode(),
                    sources.title(workflow.sourceType(), workflow.sourceId())));
        });
    }

    /** After a Gmail sync run was closed. */
    public void syncFinished(UUID accountId, UUID runId) {
        safely("syncFinished", () -> {
            RunRow run = runs.find(runId).orElse(null);
            if (run == null) {
                return;
            }
            if (run.status() != SyncRunStatus.FAILED) {
                notifications.notify(NotificationRules.newResearch(runId, run.counters().imported));
                return;
            }
            List<UUID> streak = new ArrayList<>();
            for (RunRow recent : runs.recentFinished(accountId, NotificationRules.SYNC_FAILURE_STREAK + 7)) {
                if (recent.status() != SyncRunStatus.FAILED) {
                    break;
                }
                streak.add(recent.id());
            }
            boolean reconnect = accounts.find(accountId).map(a -> a.status() == GmailAccountStatus.RECONNECT_REQUIRED).orElse(false);
            notifications.notify(NotificationRules.syncFailed(accountId, streak, reconnect, run.errorCode()));
        });
    }

    @EventListener
    public void onAssessmentCreated(ImpactAssessmentCreatedEvent event) {
        safely("assessment", () -> {
            if (event.triggerSourceId() == null) {
                return;
            }
            WorkflowSourceType type = "DOCUMENT".equals(event.triggerSourceType()) ? WorkflowSourceType.DOCUMENT : WorkflowSourceType.EMAIL;
            notifications.notifyAll(NotificationRules.risks(event, relevance.portfolioNames().get(event.portfolioId()),
                    sources.title(type, event.triggerSourceId())));
        });
    }

    @EventListener
    public void onBriefGenerated(BriefGeneratedEvent event) {
        safely("brief", () -> {
            String name = event.portfolioId() == null ? null : relevance.portfolioNames().get(event.portfolioId());
            notifications.notify(NotificationRules.briefReady(event, name));
            notifications.notifyAll(NotificationRules.conflicts(event, name));
        });
    }

    @EventListener
    public void onBriefJobFailed(BriefJobFailedEvent event) {
        safely("briefJobFailed", () -> notifications.notify(NotificationRules.briefJobFailed(event.jobId(), event.portfolioId(),
                String.valueOf(event.businessDate()), event.errorCode())));
    }

    private static void safely(String trigger, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Notification trigger {} failed: {}", trigger, e.toString());
        }
    }
}

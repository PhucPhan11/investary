package com.investary.automation.application.notification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.investary.automation.domain.NotificationEntity;
import com.investary.automation.domain.NotificationPriority;
import com.investary.automation.domain.NotificationType;
import com.investary.automation.persistence.NotificationStore.NewRow;
import com.investary.common.util.Hashing;
import com.investary.intelligence.application.BriefGeneratedEvent;
import com.investary.intelligence.application.ImpactAssessmentCreatedEvent;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.ImpactCategory;
import com.investary.intelligence.domain.ResearchState;
import com.investary.knowledge.assistant.EvidenceStatus;

/**
 * Deterministic notification eligibility (Phase 5 / Milestone 4). Rules read stored, validated facts only:
 * holding matches, assessment categories with their evidence status, explicit-rating conflicts and job states.
 * No model is called and no wording from an email (promotional or otherwise) decides anything. Every result
 * carries a deduplication key, so the same fact never notifies twice.
 */
public final class NotificationRules {

    /** Consecutive failed Gmail runs before the user is told. */
    public static final int SYNC_FAILURE_STREAK = 3;

    private NotificationRules() {
    }

    /** New research that directly concerns holdings of one portfolio. */
    public static Optional<NewRow> portfolioResearch(NotificationEntity sourceType, UUID sourceId, String sourceTitle, UUID portfolioId,
                                                     String portfolioName, List<String> symbols) {
        if (sourceId == null || portfolioId == null || symbols.isEmpty()) {
            return Optional.empty();
        }
        String held = String.join(", ", symbols);
        return Optional.of(new NewRow(NotificationType.PORTFOLIO_RESEARCH, NotificationPriority.NORMAL,
                "New research on " + held,
                "\"" + title(sourceTitle) + "\" mentions " + held + ", held in " + name(portfolioName) + ".",
                sourceType, sourceId, portfolioId, "portfolio-research:" + sourceId + ":" + portfolioId));
    }

    /** One summary per Gmail sync run that imported mail. */
    public static Optional<NewRow> newResearch(UUID runId, int imported) {
        if (runId == null || imported <= 0) {
            return Optional.empty();
        }
        return Optional.of(new NewRow(NotificationType.NEW_RESEARCH, NotificationPriority.LOW,
                imported == 1 ? "1 new research email" : imported + " new research emails",
                "Gmail sync imported " + imported + (imported == 1 ? " email" : " emails") + " from approved brokerage sources.",
                NotificationEntity.GMAIL, runId, null, "new-research:" + runId));
    }

    /**
     * Material, source-backed risks: an automatic assessment item whose validated category is
     * {@code POTENTIAL_RISK}, whose evidence fully supports it, about a directly held instrument.
     */
    public static List<NewRow> risks(ImpactAssessmentCreatedEvent event, String portfolioName, String sourceTitle) {
        List<NewRow> rows = new ArrayList<>();
        if (event.triggerSourceId() == null) {
            return rows; // the user asked for this assessment and is looking at it
        }
        for (ImpactAssessmentCreatedEvent.Item item : event.items()) {
            boolean material = item.category() == ImpactCategory.POTENTIAL_RISK && item.evidenceStatus() == EvidenceStatus.SUPPORTED
                    && item.deterministicCategory() == ImpactCategory.DIRECT_HOLDING && item.instrumentId() != null;
            if (material) {
                rows.add(new NewRow(NotificationType.RESEARCH_RISK, NotificationPriority.HIGH,
                        "Possible risk for " + item.symbol(),
                        "An impact assessment of " + name(portfolioName) + " found a source-backed potential risk for " + item.symbol()
                                + " in \"" + title(sourceTitle) + "\". Review the evidence; this is not a trading signal.",
                        NotificationEntity.ASSESSMENT, event.assessmentId(), event.portfolioId(),
                        "risk:" + event.portfolioId() + ":" + item.instrumentId() + ":" + event.triggerSourceId()));
            }
        }
        return rows;
    }

    /** Brokerages with explicitly different ratings for a held symbol. */
    public static List<NewRow> conflicts(BriefGeneratedEvent event, String portfolioName) {
        List<NewRow> rows = new ArrayList<>();
        if (event.portfolioId() == null || event.status() == BriefStatus.FAILED) {
            return rows;
        }
        for (BriefGeneratedEvent.Conflict conflict : event.conflicts()) {
            if (!conflict.held() || conflict.sourceIds().size() < 2) {
                continue;
            }
            String sources = String.join(",", conflict.sourceIds().stream().map(UUID::toString).sorted().toList());
            String hash = Hashing.sha256(sources.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
            rows.add(new NewRow(NotificationType.RESEARCH_CONFLICT, NotificationPriority.NORMAL,
                    "Different views on " + conflict.symbol(),
                    String.join(", ", conflict.brokerages()) + " rate " + conflict.symbol() + " differently (held in " + name(portfolioName)
                            + "). Compare both reports in the daily brief.",
                    NotificationEntity.BRIEF, event.briefId(), event.portfolioId(),
                    "conflict:" + event.portfolioId() + ":" + conflict.symbol() + ":" + hash));
        }
        return rows;
    }

    /** A new scheduled or manual brief; regenerations and unchanged inputs do not notify. */
    public static Optional<NewRow> briefReady(BriefGeneratedEvent event, String portfolioName) {
        boolean ready = event.trigger() != BriefTrigger.REGENERATION
                && (event.status() == BriefStatus.COMPLETED || event.status() == BriefStatus.PARTIAL);
        if (!ready) {
            return Optional.empty();
        }
        boolean nothingNew = event.researchState() == ResearchState.NO_MATERIAL_RESEARCH;
        String scope = event.portfolioId() == null ? "general" : event.portfolioId().toString();
        return Optional.of(new NewRow(NotificationType.DAILY_BRIEF_READY, nothingNew ? NotificationPriority.LOW : NotificationPriority.NORMAL,
                "Daily brief for " + event.businessDate() + " is ready",
                (event.portfolioId() == null ? "General brief" : "Brief for " + name(portfolioName))
                        + (nothingNew ? ": no material new research in this period." : ".")
                        + (event.status() == BriefStatus.PARTIAL ? " Some parts were generated without AI." : ""),
                NotificationEntity.BRIEF, event.briefId(), event.portfolioId(), "brief-ready:" + scope + ":" + event.periodEnd()));
    }

    /**
     * Gmail synchronisation keeps failing, or needs the user to reconnect.
     *
     * @param recentFailedRunIds the account's latest finished runs, newest first, up to the first that did not fail
     *                           (the current failure streak)
     */
    public static Optional<NewRow> syncFailed(UUID accountId, List<UUID> recentFailedRunIds, boolean reconnectRequired, String errorCode) {
        if (recentFailedRunIds.isEmpty() || !reconnectRequired && recentFailedRunIds.size() < SYNC_FAILURE_STREAK) {
            return Optional.empty();
        }
        UUID firstOfStreak = recentFailedRunIds.getLast();
        return Optional.of(new NewRow(NotificationType.SYNC_FAILED, NotificationPriority.HIGH,
                reconnectRequired ? "Reconnect Gmail" : "Gmail sync keeps failing",
                reconnectRequired ? "The Gmail authorization expired or was revoked; reconnect Gmail in the Automation Center."
                        : "The last " + recentFailedRunIds.size() + " Gmail syncs failed" + (errorCode == null ? "" : " (" + errorCode + ")")
                        + ". New research is not being imported.",
                NotificationEntity.GMAIL, accountId, null, "sync-failed:" + accountId + ":" + firstOfStreak));
    }

    /** A workflow ended FAILED: a mandatory stage could not be completed. */
    public static Optional<NewRow> workflowFailed(UUID workflowId, UUID failedJobId, String jobType, String errorCode, String sourceTitle) {
        if (workflowId == null || failedJobId == null) {
            return Optional.empty();
        }
        return Optional.of(new NewRow(NotificationType.PROCESSING_FAILED, NotificationPriority.HIGH,
                "Processing failed: " + title(sourceTitle),
                "The " + jobType + " step failed" + (errorCode == null ? "" : " (" + errorCode + ")")
                        + ". Open the job to see the details and retry.",
                NotificationEntity.JOB, failedJobId, null, "workflow-failed:" + workflowId + ":" + failedJobId));
    }

    /** A brief generation job failed permanently. */
    public static Optional<NewRow> briefJobFailed(UUID jobId, UUID portfolioId, String businessDate, String errorCode) {
        return Optional.of(new NewRow(NotificationType.PROCESSING_FAILED, NotificationPriority.HIGH,
                "Daily brief for " + businessDate + " failed",
                "Brief generation failed" + (errorCode == null ? "" : " (" + errorCode + ")") + ". Retry it from the Automation Center.",
                NotificationEntity.BRIEF_JOB, jobId, portfolioId, "brief-job-failed:" + jobId));
    }

    private static String title(String sourceTitle) {
        return sourceTitle == null || sourceTitle.isBlank() ? "(untitled source)" : sourceTitle.strip();
    }

    private static String name(String portfolioName) {
        return portfolioName == null || portfolioName.isBlank() ? "a portfolio" : portfolioName;
    }
}

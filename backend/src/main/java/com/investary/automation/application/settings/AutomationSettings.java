package com.investary.automation.application.settings;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.investary.automation.GmailProperties;
import com.investary.automation.WorkflowProperties;
import com.investary.common.settings.RuntimeSettings;
import com.investary.common.settings.SettingField;
import com.investary.common.settings.SettingType;
import com.investary.common.settings.SettingsContributor;

/**
 * Effective Gmail and research-processing settings: {@link GmailProperties} / {@link WorkflowProperties} with the
 * user's overrides applied (ADR 0012). Read per use, so a saved change applies without a restart, except the
 * worker pool size, which is read once at start-up.
 */
@Component
public class AutomationSettings implements SettingsContributor {

    public static final String GMAIL = "gmail";
    public static final String PROCESSING = "processing";
    public static final String AI = "ai";

    private final GmailProperties gmail;
    private final WorkflowProperties workflow;
    private final RuntimeSettings settings;
    private final int maxManualRetries;
    private final Duration stuckJobAfter;

    public AutomationSettings(GmailProperties gmail, WorkflowProperties workflow, RuntimeSettings settings,
                              @Value("${investary.workflow.max-manual-retries:5}") int maxManualRetries,
                              @Value("${investary.workflow.stuck-job-after:1h}") Duration stuckJobAfter) {
        this.gmail = gmail;
        this.workflow = workflow;
        this.settings = settings;
        this.maxManualRetries = Math.max(1, maxManualRetries);
        this.stuckJobAfter = stuckJobAfter;
    }

    /** Scheduled polling is on: the configuration allows it and the user did not switch it off. */
    public boolean pollingEnabled() {
        return gmail.sync().schedulerEnabled() && settings.bool(GMAIL, "pollingEnabled", true);
    }

    public boolean schedulerConfigured() {
        return gmail.sync().schedulerEnabled();
    }

    /** {@link GmailProperties.Sync} with the interval, lookback and per-run limit overrides applied. */
    public GmailProperties.Sync gmailSync() {
        GmailProperties.Sync base = gmail.sync();
        Duration interval = settings.number(GMAIL, "intervalMinutes").map(Duration::ofMinutes).orElse(base.interval());
        return new GmailProperties.Sync(base.schedulerEnabled(), interval,
                settings.integer(GMAIL, "initialLookbackDays", base.initialLookbackDays()),
                settings.integer(GMAIL, "maxMessagesPerRun", base.maxMessagesPerRun()),
                base.recoveryMaxLookbackDays(), base.lockLease(), base.pageSize());
    }

    /** {@link WorkflowProperties} with the processing and analysis-limit overrides applied. */
    public WorkflowProperties workflow() {
        WorkflowProperties base = workflow;
        Duration backoff = settings.number(PROCESSING, "retryBackoffSeconds").map(Duration::ofSeconds).orElse(base.retryBackoff());
        return new WorkflowProperties(base.enabled(),
                settings.integer(PROCESSING, "maxAttempts", base.maxAttempts()), backoff, base.waitPoll(),
                base.downloadWaitTimeout(), base.indexWaitTimeout(),
                settings.integer(PROCESSING, "maxDownloadsPerEmail", base.maxDownloadsPerEmail()),
                settings.integer(PROCESSING, "maxDownloadsPerSyncRun", base.maxDownloadsPerSyncRun()),
                settings.bool(PROCESSING, "autoAssess", base.autoAssess()),
                settings.integer(AI, "maxAutoAssessmentsPerDay", base.maxAutoAssessmentsPerDay()),
                base.assessMarketContext(), base.worker());
    }

    /** Global switch for automatic PDF downloads; a source rule must still opt in. */
    public boolean autoPdfDownloadEnabled() {
        return settings.bool(PROCESSING, "autoPdfDownloadEnabled", true);
    }

    /** Worker threads; read once when the worker starts. */
    public int workerConcurrency() {
        return settings.integer(PROCESSING, "workerConcurrency", workflow.worker().concurrency());
    }

    public int maxManualRetries() {
        return maxManualRetries;
    }

    public Duration stuckJobAfter() {
        return stuckJobAfter;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.bool(GMAIL, "pollingEnabled", 0, "Automatic polling", "Sync Gmail on a schedule", () -> true),
                SettingField.integer(GMAIL, "intervalMinutes", 1, "Polling interval (minutes)", "Time between scheduled syncs",
                        () -> gmail.sync().interval().toMinutes(), 5, 1440),
                SettingField.integer(GMAIL, "initialLookbackDays", 2, "Initial synchronisation window (days)",
                        "How far back the first sync of a newly connected account reads", () -> gmail.sync().initialLookbackDays(), 1, 365),
                SettingField.integer(GMAIL, "maxMessagesPerRun", 3, "Max messages per synchronisation",
                        "Matching messages handled per run; the rest continue in the next run", () -> gmail.sync().maxMessagesPerRun(), 10, 2000),
                SettingField.bool(GMAIL, "schedulerConfigured", 4, "Scheduler available",
                        "INVESTARY_GMAIL_SYNC_SCHEDULER_ENABLED; when false no scheduled sync runs", () -> gmail.sync().schedulerEnabled())
                        .withReadOnly(),

                SettingField.bool(PROCESSING, "autoPdfDownloadEnabled", 0, "Automatic PDF download",
                        "Master switch; a source rule must also enable automatic PDF download", () -> true),
                SettingField.integer(PROCESSING, "maxDownloadsPerEmail", 1, "Max PDF downloads per email", null,
                        workflow::maxDownloadsPerEmail, 0, 50),
                SettingField.integer(PROCESSING, "maxDownloadsPerSyncRun", 2, "Max PDF downloads per batch (sync run)", null,
                        workflow::maxDownloadsPerSyncRun, 0, 200),
                SettingField.integer(PROCESSING, "maxAttempts", 3, "Max attempts per job", "Applies to new jobs and retries",
                        workflow::maxAttempts, 1, 10),
                SettingField.integer(PROCESSING, "retryBackoffSeconds", 4, "Retry backoff (seconds)",
                        "First retry delay; doubles per attempt", () -> workflow.retryBackoff().toSeconds(), 1, 3600),
                SettingField.bool(PROCESSING, "autoAssess", 5, "Automatic impact assessment",
                        "Assess portfolios with a direct match automatically", workflow::autoAssess),
                SettingField.integer(PROCESSING, "workerConcurrency", 6, "Background job concurrency", null,
                        () -> workflow.worker().concurrency(), 1, 8).withRestart(),
                SettingField.bool(PROCESSING, "workflowsEnabled", 7, "Automatic workflows",
                        "INVESTARY_WORKFLOW_ENABLED; when false new sources are only processed on request", workflow::enabled).withReadOnly(),
                SettingField.of(PROCESSING, "maxManualRetries", SettingType.INTEGER, 8, "Max manual retries per job",
                        "investary.workflow.max-manual-retries", () -> maxManualRetries).withReadOnly(),

                SettingField.integer(AI, "maxAutoAssessmentsPerDay", 0, "Daily analysis limit",
                        "New automatic impact assessments per day (Asia/Ho_Chi_Minh)", workflow::maxAutoAssessmentsPerDay, 0, 200));
    }
}

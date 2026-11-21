import { SettingField } from '../../core/models/automation.model';

/**
 * UI texts of the runtime settings, keyed by "<section>.<field>". The backend sends English labels and help with
 * each field (`SettingsContributor`); a field missing here falls back to them, so a new backend field still shows.
 */
const FIELD_TEXT: Record<string, { label: string; help?: string }> = {
  'gmail.pollingEnabled': {
    label: $localize`:@@setting.gmail.pollingEnabled:Automatic polling`,
    help: $localize`:@@setting.gmail.pollingEnabled.help:Sync Gmail on a schedule`,
  },
  'gmail.intervalMinutes': {
    label: $localize`:@@setting.gmail.intervalMinutes:Polling interval (minutes)`,
    help: $localize`:@@setting.gmail.intervalMinutes.help:Time between scheduled syncs`,
  },
  'gmail.initialLookbackDays': {
    label: $localize`:@@setting.gmail.initialLookbackDays:Initial synchronisation window (days)`,
    help: $localize`:@@setting.gmail.initialLookbackDays.help:How far back the first sync of a newly connected account reads`,
  },
  'gmail.maxMessagesPerRun': {
    label: $localize`:@@setting.gmail.maxMessagesPerRun:Max messages per synchronisation`,
    help: $localize`:@@setting.gmail.maxMessagesPerRun.help:Matching messages handled per run; the rest continue in the next run`,
  },
  'gmail.schedulerConfigured': {
    label: $localize`:@@setting.gmail.schedulerConfigured:Scheduler available`,
    help: $localize`:@@setting.gmail.schedulerConfigured.help:INVESTARY_GMAIL_SYNC_SCHEDULER_ENABLED; when false no scheduled sync runs`,
  },
  'processing.autoPdfDownloadEnabled': {
    label: $localize`:@@setting.processing.autoPdfDownloadEnabled:Automatic PDF download`,
    help: $localize`:@@setting.processing.autoPdfDownloadEnabled.help:Master switch; a source rule must also enable automatic PDF download`,
  },
  'processing.maxDownloadsPerEmail': {
    label: $localize`:@@setting.processing.maxDownloadsPerEmail:Max PDF downloads per email`,
  },
  'processing.maxDownloadsPerSyncRun': {
    label: $localize`:@@setting.processing.maxDownloadsPerSyncRun:Max PDF downloads per batch (sync run)`,
  },
  'processing.maxAttempts': {
    label: $localize`:@@setting.processing.maxAttempts:Max attempts per job`,
    help: $localize`:@@setting.processing.maxAttempts.help:Applies to new jobs and retries`,
  },
  'processing.retryBackoffSeconds': {
    label: $localize`:@@setting.processing.retryBackoffSeconds:Retry backoff (seconds)`,
    help: $localize`:@@setting.processing.retryBackoffSeconds.help:First retry delay; doubles per attempt`,
  },
  'processing.autoAssess': {
    label: $localize`:@@setting.processing.autoAssess:Automatic impact assessment`,
    help: $localize`:@@setting.processing.autoAssess.help:Assess portfolios with a direct match automatically`,
  },
  'processing.workerConcurrency': {
    label: $localize`:@@setting.processing.workerConcurrency:Background job concurrency`,
  },
  'processing.workflowsEnabled': {
    label: $localize`:@@setting.processing.workflowsEnabled:Automatic workflows`,
    help: $localize`:@@setting.processing.workflowsEnabled.help:INVESTARY_WORKFLOW_ENABLED; when false new sources are only processed on request`,
  },
  'processing.maxManualRetries': {
    label: $localize`:@@setting.processing.maxManualRetries:Max manual retries per job`,
  },
  'ai.maxAutoAssessmentsPerDay': {
    label: $localize`:@@setting.ai.maxAutoAssessmentsPerDay:Daily analysis limit`,
    help: $localize`:@@setting.ai.maxAutoAssessmentsPerDay.help:New automatic impact assessments per day (Asia/Ho_Chi_Minh)`,
  },
  'ai.maxAiBriefsPerDay': {
    label: $localize`:@@setting.ai.maxAiBriefsPerDay:Daily brief generation limit`,
    help: $localize`:@@setting.ai.maxAiBriefsPerDay.help:Briefs per day whose AI part is generated; later briefs are deterministic only`,
  },
  'ai.maxConcurrentChat': {
    label: $localize`:@@setting.ai.maxConcurrentChat:Max concurrent AI (chat) requests`,
    help: $localize`:@@setting.ai.wholeApplication:Whole application`,
  },
  'ai.maxConcurrentEmbedding': {
    label: $localize`:@@setting.ai.maxConcurrentEmbedding:Max concurrent embedding requests`,
    help: $localize`:@@setting.ai.wholeApplication:Whole application`,
  },
  'ai.dailyAutomaticTokenBudget': {
    label: $localize`:@@setting.ai.dailyAutomaticTokenBudget:Daily token budget for automatic AI calls`,
    help: $localize`:@@setting.ai.dailyAutomaticTokenBudget.help:Provider-reported tokens of automatic assessments and briefs per day; empty means no budget`,
  },
  'brief.enabled': {
    label: $localize`:@@setting.brief.enabled:Daily brief`,
    help: $localize`:@@setting.brief.enabled.help:Generate scheduled briefs and accept manual requests`,
  },
  'brief.scheduleTime': {
    label: $localize`:@@setting.brief.scheduleTime:Scheduled time`,
    help: $localize`:@@setting.brief.scheduleTime.help:Local time in the brief time zone`,
  },
  'brief.zone': {
    label: $localize`:@@setting.brief.zone:Time zone`,
    help: $localize`:@@setting.brief.zone.help:IANA time zone of the schedule and reporting periods`,
  },
  'brief.defaultPortfolioId': {
    label: $localize`:@@setting.brief.defaultPortfolioId:Default portfolio`,
    help: $localize`:@@setting.brief.defaultPortfolioId.help:Scheduled briefs cover this portfolio only; empty means every portfolio`,
  },
  'brief.noResearchMode': {
    label: $localize`:@@setting.brief.noResearchMode:When there is no new research`,
    help: $localize`:@@setting.brief.noResearchMode.help:MINIMAL writes a short brief; SKIP only records the period`,
  },
};

export function settingLabel(section: string, field: SettingField): string {
  return FIELD_TEXT[`${section}.${field.name}`]?.label ?? field.label;
}

/**
 * Help text in the UI language. The backend's help is kept when it carries live details the static text does not
 * (for example the custom cron of the brief schedule).
 */
export function settingHelp(section: string, field: SettingField): string | null {
  if (!field.help) {
    return null;
  }
  if (field.name === 'scheduleTime' && field.help.includes('cron')) {
    return field.help;
  }
  return FIELD_TEXT[`${section}.${field.name}`]?.help ?? field.help;
}

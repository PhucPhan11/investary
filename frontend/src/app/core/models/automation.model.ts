export type GmailAccountStatus = 'CONNECTED' | 'RECONNECT_REQUIRED' | 'DISCONNECTED';
export type SyncRunStatus = 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED';

export interface GmailAccount {
  id: string;
  emailAddress: string;
  status: GmailAccountStatus;
  grantedScopes: string[];
  connectedAt: string | null;
  initialSyncCompleted: boolean;
  lastSyncStartedAt: string | null;
  lastSyncSuccessAt: string | null;
  lastErrorCode: string | null;
  lastError: string | null;
}

export interface SyncRun {
  id: string;
  trigger: 'MANUAL' | 'SCHEDULED';
  mode: 'INITIAL' | 'INCREMENTAL' | 'RECOVERY' | null;
  status: SyncRunStatus;
  startedAt: string;
  finishedAt: string | null;
  listed: number;
  imported: number;
  duplicates: number;
  notImported: number;
  skipped: number;
  rejected: number;
  errorCode: string | null;
  errorMessage: string | null;
  warning: string | null;
}

/** Never contains tokens or client secrets. */
export interface GmailStatus {
  configured: boolean;
  account: GmailAccount | null;
  running: boolean;
  lastRun: SyncRun | null;
  importedTotal: number;
  schedulerEnabled: boolean;
  syncIntervalMinutes: number;
  initialLookbackDays: number;
  /** Start of the latest failed run, if any. */
  lastFailedSyncAt: string | null;
  /** When the next scheduled sync is due; null when none will run. */
  nextScheduledSyncAt: string | null;
  /** The configuration allows scheduled syncs at all. */
  schedulerConfigured: boolean;
}

export interface SourceRule {
  id: string;
  name: string;
  senderAddresses: string[];
  senderDomains: string[];
  subjectKeywords: string[];
  gmailQuery: string | null;
  enabled: boolean;
  autoImport: boolean;
  autoPdfDownload: boolean;
  createdAt: string;
  updatedAt: string;
  /** PUBLIC_MAIL_DOMAIN, MANY_DOMAINS, NO_SUBJECT_FILTER */
  warnings: string[];
}

export interface SourceRuleRequest {
  name: string;
  senderAddresses: string[];
  senderDomains: string[];
  subjectKeywords: string[];
  gmailQuery: string | null;
  enabled: boolean;
  autoImport: boolean;
  autoPdfDownload: boolean;
  /** Required by the backend to add a public mailbox domain such as gmail.com. */
  acknowledgeBroadRule?: boolean;
}

export type WorkflowStatus = 'NOT_STARTED' | 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'SKIPPED';
export type WorkflowStageName =
  | 'EMAIL_IMPORTED'
  | 'EMAIL_PARSED'
  | 'LINKS_CLASSIFIED'
  | 'DOCUMENTS_PROCESSED'
  | 'KNOWLEDGE_INDEXED'
  | 'PORTFOLIO_MATCHED'
  | 'IMPACT_ASSESSED'
  | 'COMPLETED';
export type AutomationJobType = 'PREPARE' | 'DOWNLOADS' | 'INDEX' | 'MATCH' | 'ASSESS';
export type AutomationJobStatus = 'PENDING' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED';

export interface WorkflowStageView {
  stage: WorkflowStageName;
  status: Exclude<WorkflowStatus, 'NOT_STARTED'>;
  reason: string | null;
  counts: Record<string, number | string>;
  startedAt: string | null;
  completedAt: string | null;
}

export interface WorkflowMatch {
  portfolioId: string;
  instrumentId: string | null;
  symbol: string | null;
  matchKind: 'DIRECT' | 'MARKET_CONTEXT';
  publishedOn: string | null;
  matchedAt: string;
}

export interface AutomationJob {
  id: string;
  type: AutomationJobType;
  workflowId: string;
  sourceType: 'EMAIL' | 'DOCUMENT' | null;
  sourceId: string | null;
  portfolioId: string | null;
  status: AutomationJobStatus;
  outcome: string | null;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  errorCode: string | null;
  errorSummary: string | null;
  processingVersion: string;
  inputTokens: number | null;
  outputTokens: number | null;
  createdAt: string;
  updatedAt: string;
  /** Email subject or document file name; null when the source was deleted. */
  sourceTitle?: string | null;
  /** A manual retry is allowed now (decided by the backend). */
  retryable?: boolean;
  retryBlockedReason?: string | null;
  manualRetries?: number;
}

/** Automated research processing of one email or library document (Phase 5 / Milestone 2). */
export interface ResearchWorkflow {
  id: string | null;
  sourceType: 'EMAIL' | 'DOCUMENT';
  sourceId: string;
  status: WorkflowStatus;
  trigger: string | null;
  processingVersion: string | null;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string | null;
  importedBeforeAutomation: boolean;
  stages: WorkflowStageView[];
  matches: WorkflowMatch[];
  jobs: AutomationJob[];
}

export interface JobAttempt {
  attempt: number;
  trigger: 'AUTO' | 'MANUAL_RETRY' | 'LEASE_RECOVERY';
  startedAt: string;
  finishedAt: string | null;
  result: 'RUNNING' | 'SUCCEEDED' | 'RETRY_SCHEDULED' | 'WAITING' | 'FAILED' | 'LEASE_EXPIRED' | 'RETRY_REQUESTED';
  errorCode: string | null;
  errorSummary: string | null;
}

export interface JobDetail {
  job: AutomationJob;
  workflow: ResearchWorkflow | null;
  attempts: JobAttempt[];
}

export interface JobFilters {
  status?: AutomationJobStatus | '';
  type?: AutomationJobType | '';
  sourceType?: 'EMAIL' | 'DOCUMENT' | '';
  ruleId?: string;
  from?: string;
  to?: string;
}

// ---- Automation Center (Phase 5 / Milestone 4) ----

export type OverviewPeriod = '24h' | '7d' | '30d';
export type HealthState = 'OPERATIONAL' | 'DEGRADED' | 'DISABLED' | 'UNAVAILABLE';

export interface ComponentHealth {
  component: 'GMAIL' | 'RESEARCH_INGESTION' | 'DOCUMENT_PROCESSING' | 'KNOWLEDGE_INDEXING' | 'BRIEF_GENERATION';
  state: HealthState;
  reason: string;
  lastActivityAt: string | null;
  checkedAt: string;
}

export interface OverviewMetrics {
  emailsImported: number;
  emailsProcessed: number;
  reportsDownloaded: number;
  documentsIndexed: number;
  assessmentsCreated: number;
  /** Failed in the window. */
  failedJobs: number;
  /** Queued or running now (not limited to the window). */
  pendingJobs: number;
  briefsGenerated: number;
}

export interface OverviewReport {
  period: OverviewPeriod;
  from: string;
  to: string;
  gmail: GmailStatus;
  metrics: OverviewMetrics;
  health: ComponentHealth[];
}

export type SettingValue = boolean | number | string | null;

export interface SettingField {
  name: string;
  label: string;
  help: string | null;
  type: 'BOOLEAN' | 'INTEGER' | 'TIME' | 'ZONE' | 'UUID' | 'ENUM' | 'TEXT';
  value: SettingValue;
  defaultValue: SettingValue;
  overridden: boolean;
  min: number | null;
  max: number | null;
  options: string[];
  nullable: boolean;
  appliesAfterRestart: boolean;
  readOnly: boolean;
}

export interface SettingsSection {
  section: 'gmail' | 'processing' | 'brief' | 'ai';
  version: number;
  fields: SettingField[];
}

export interface UsageBucket {
  start: string;
  chatRequests: number;
  chatFailures: number;
  embeddingRequests: number;
  embeddingFailures: number;
  inputTokens: number;
  outputTokens: number;
  briefGenerations: number;
  aiBriefGenerations: number;
  analyses: number;
  automaticAnalyses: number;
}

export interface UsagePurposeTotal {
  operation: 'CHAT' | 'EMBEDDING';
  purpose: string;
  model: string;
  requests: number;
  failures: number;
  inputTokens: number;
  outputTokens: number;
}

export interface UsageLimits {
  maxAutoAssessmentsPerDay: number;
  automaticAssessmentsToday: number;
  maxAiBriefsPerDay: number;
  aiBriefsToday: number;
  dailyTokenBudget: number | null;
  automaticTokensToday: number;
  maxConcurrentChat: number;
  maxConcurrentEmbedding: number;
}

export interface AiUsage {
  period: 'day' | 'week';
  from: string;
  to: string;
  /** First day with recorded requests; earlier activity has no counters. */
  trackingSince: string | null;
  buckets: UsageBucket[];
  byPurpose: UsagePurposeTotal[];
  limits: UsageLimits;
}

// ---- Notifications ----

export type NotificationType =
  | 'NEW_RESEARCH'
  | 'PORTFOLIO_RESEARCH'
  | 'RESEARCH_RISK'
  | 'RESEARCH_CONFLICT'
  | 'DAILY_BRIEF_READY'
  | 'SYNC_FAILED'
  | 'PROCESSING_FAILED';

export interface AppNotification {
  id: string;
  type: NotificationType;
  priority: 'LOW' | 'NORMAL' | 'HIGH';
  title: string;
  message: string;
  entityType: 'EMAIL' | 'DOCUMENT' | 'BRIEF' | 'ASSESSMENT' | 'PORTFOLIO' | 'JOB' | 'BRIEF_JOB' | 'GMAIL' | null;
  entityId: string | null;
  portfolioId: string | null;
  createdAt: string;
  readAt: string | null;
  read: boolean;
}

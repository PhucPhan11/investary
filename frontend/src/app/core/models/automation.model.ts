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

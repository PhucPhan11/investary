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

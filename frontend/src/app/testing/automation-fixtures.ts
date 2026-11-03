import { AppNotification, AutomationJob } from '../core/models/automation.model';

/** Synthetic test data for the Automation Center specs. */

export function job(overrides: Partial<AutomationJob> = {}): AutomationJob {
  return {
    id: 'j1-0000-0000',
    type: 'INDEX',
    workflowId: 'w1',
    sourceType: 'EMAIL',
    sourceId: 'e1',
    portfolioId: null,
    status: 'FAILED',
    outcome: null,
    attempts: 3,
    maxAttempts: 3,
    nextAttemptAt: null,
    startedAt: '2026-11-01T01:00:00Z',
    completedAt: '2026-11-01T01:05:00Z',
    errorCode: 'INDEXING_FAILED',
    errorSummary: 'Embedding provider offline',
    processingVersion: 'w1',
    inputTokens: null,
    outputTokens: null,
    createdAt: '2026-11-01T01:00:00Z',
    updatedAt: '2026-11-01T01:05:00Z',
    sourceTitle: 'IMP cập nhật',
    retryable: true,
    retryBlockedReason: null,
    manualRetries: 0,
    ...overrides,
  };
}

export function notification(overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id: 'n1',
    type: 'PORTFOLIO_RESEARCH',
    priority: 'NORMAL',
    title: 'New research on IMP',
    message: '"IMP Q3" mentions IMP, held in Main.',
    entityType: 'EMAIL',
    entityId: 'e1',
    portfolioId: 'p1',
    createdAt: '2026-11-01T02:00:00Z',
    readAt: null,
    read: false,
    ...overrides,
  };
}

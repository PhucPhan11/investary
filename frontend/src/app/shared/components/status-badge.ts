import { Component, computed, input } from '@angular/core';

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'muted';

const BADGES: Record<string, { label: string; tone: Tone }> = {
  // Link status
  DISCOVERED: { label: $localize`:@@badge.DISCOVERED:Discovered`, tone: 'neutral' },
  READY: { label: $localize`:@@badge.READY:Ready`, tone: 'info' },
  DOWNLOADING: { label: $localize`:@@badge.DOWNLOADING:Downloading…`, tone: 'warning' },
  DOWNLOADED: { label: $localize`:@@badge.DOWNLOADED:Downloaded`, tone: 'success' },
  FAILED: { label: $localize`:@@badge.FAILED:Failed`, tone: 'danger' },
  SKIPPED: { label: $localize`:@@badge.SKIPPED:Skipped`, tone: 'muted' },
  // Parsing status
  PARSED: { label: $localize`:@@badge.PARSED:Parsed`, tone: 'success' },
  PARTIAL: { label: $localize`:@@badge.PARTIAL:Partially parsed`, tone: 'warning' },
  // Knowledge base index status
  NOT_INDEXED: { label: $localize`:@@badge.NOT_INDEXED:Not indexed`, tone: 'neutral' },
  PENDING: { label: $localize`:@@badge.PENDING:Queued`, tone: 'info' },
  PROCESSING: { label: $localize`:@@badge.PROCESSING:Indexing…`, tone: 'warning' },
  INDEXED: { label: $localize`:@@badge.INDEXED:Indexed`, tone: 'success' },
  // Link category
  RESEARCH_REPORT: { label: $localize`:@@badge.RESEARCH_REPORT:Research report`, tone: 'info' },
  STOCK_RESEARCH: { label: $localize`:@@badge.STOCK_RESEARCH:Stock research`, tone: 'info' },
  MARKET_REPORT: { label: $localize`:@@badge.MARKET_REPORT:Market report`, tone: 'info' },
  NAVIGATION: { label: $localize`:@@badge.NAVIGATION:Navigation`, tone: 'muted' },
  PROMOTION: { label: $localize`:@@badge.PROMOTION:Promotion`, tone: 'muted' },
  OTHER: { label: $localize`:@@badge.OTHER:Other`, tone: 'neutral' },
  // Gmail integration and sync runs
  NOT_CONFIGURED: { label: $localize`:@@badge.NOT_CONFIGURED:Not configured`, tone: 'muted' },
  CONNECTED: { label: $localize`:@@badge.CONNECTED:Connected`, tone: 'success' },
  RECONNECT_REQUIRED: { label: $localize`:@@badge.RECONNECT_REQUIRED:Reconnect required`, tone: 'danger' },
  DISCONNECTED: { label: $localize`:@@badge.DISCONNECTED:Not connected`, tone: 'neutral' },
  RUNNING: { label: $localize`:@@badge.RUNNING:Running…`, tone: 'warning' },
  SUCCEEDED: { label: $localize`:@@badge.SUCCEEDED:Succeeded`, tone: 'success' },
  SYNC_PARTIAL: { label: $localize`:@@badge.SYNC_PARTIAL:Partial (continues)`, tone: 'warning' },
  // Research workflows and their stages (prefixed: PARTIAL/FAILED mean something else for parsing)
  WORKFLOW_NOT_STARTED: { label: $localize`:@@badge.WORKFLOW_NOT_STARTED:Not started`, tone: 'neutral' },
  WORKFLOW_PENDING: { label: $localize`:@@badge.WORKFLOW_PENDING:Waiting`, tone: 'neutral' },
  WORKFLOW_RUNNING: { label: $localize`:@@badge.WORKFLOW_RUNNING:Running…`, tone: 'warning' },
  WORKFLOW_COMPLETED: { label: $localize`:@@badge.WORKFLOW_COMPLETED:Completed`, tone: 'success' },
  WORKFLOW_PARTIAL: { label: $localize`:@@badge.WORKFLOW_PARTIAL:Partial`, tone: 'warning' },
  WORKFLOW_FAILED: { label: $localize`:@@badge.WORKFLOW_FAILED:Failed`, tone: 'danger' },
  WORKFLOW_SKIPPED: { label: $localize`:@@badge.WORKFLOW_SKIPPED:Skipped`, tone: 'muted' },
  // Daily briefs (prefixed for the same reason)
  BRIEF_COMPLETED: { label: $localize`:@@badge.BRIEF_COMPLETED:Completed`, tone: 'success' },
  BRIEF_PARTIAL: { label: $localize`:@@badge.BRIEF_PARTIAL:Partial (no AI part)`, tone: 'warning' },
  BRIEF_FAILED: { label: $localize`:@@badge.BRIEF_FAILED:Failed`, tone: 'danger' },
  BRIEF_SKIPPED: { label: $localize`:@@badge.BRIEF_SKIPPED:Skipped (nothing new)`, tone: 'muted' },
  // Automation jobs (Automation Center)
  WAITING: { label: $localize`:@@badge.WAITING:Waiting`, tone: 'info' },
  // Component health (prefixed: health is not a job status)
  HEALTH_OPERATIONAL: { label: $localize`:@@badge.HEALTH_OPERATIONAL:Operational`, tone: 'success' },
  HEALTH_DEGRADED: { label: $localize`:@@badge.HEALTH_DEGRADED:Degraded`, tone: 'warning' },
  HEALTH_DISABLED: { label: $localize`:@@badge.HEALTH_DISABLED:Disabled`, tone: 'muted' },
  HEALTH_UNAVAILABLE: { label: $localize`:@@badge.HEALTH_UNAVAILABLE:Unavailable`, tone: 'danger' },
  // Decision journal (prefixed: CANCELLED/DRAFT are decision states)
  DECISION_DRAFT: { label: $localize`:@@badge.DECISION_DRAFT:Draft`, tone: 'neutral' },
  DECISION_DECIDED: { label: $localize`:@@badge.DECISION_DECIDED:Decided`, tone: 'info' },
  DECISION_EXECUTED: { label: $localize`:@@badge.DECISION_EXECUTED:Executed`, tone: 'success' },
  DECISION_CANCELLED: { label: $localize`:@@badge.DECISION_CANCELLED:Cancelled`, tone: 'muted' },
  // Notification priority
  NOTIF_HIGH: { label: $localize`:@@badge.NOTIF_HIGH:High`, tone: 'danger' },
  NOTIF_NORMAL: { label: $localize`:@@badge.NOTIF_NORMAL:Normal`, tone: 'info' },
  NOTIF_LOW: { label: $localize`:@@badge.NOTIF_LOW:Low`, tone: 'muted' },
};

/** Small coloured label for statuses and categories returned by the API. */
@Component({
  selector: 'app-status-badge',
  template: `<span [class]="'badge badge--' + badge().tone">{{ badge().label }}</span>`,
})
export class StatusBadge {
  readonly value = input.required<string>();
  protected readonly badge = computed(
    () => BADGES[this.value()] ?? { label: this.value(), tone: 'neutral' as Tone },
  );
}

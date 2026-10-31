import { Component, computed, input } from '@angular/core';

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'muted';

const BADGES: Record<string, { label: string; tone: Tone }> = {
  // Link status
  DISCOVERED: { label: 'Discovered', tone: 'neutral' },
  READY: { label: 'Ready', tone: 'info' },
  DOWNLOADING: { label: 'Downloading…', tone: 'warning' },
  DOWNLOADED: { label: 'Downloaded', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  SKIPPED: { label: 'Skipped', tone: 'muted' },
  // Parsing status
  PARSED: { label: 'Parsed', tone: 'success' },
  PARTIAL: { label: 'Partially parsed', tone: 'warning' },
  // Knowledge base index status
  NOT_INDEXED: { label: 'Not indexed', tone: 'neutral' },
  PENDING: { label: 'Queued', tone: 'info' },
  PROCESSING: { label: 'Indexing…', tone: 'warning' },
  INDEXED: { label: 'Indexed', tone: 'success' },
  // Link category
  RESEARCH_REPORT: { label: 'Research report', tone: 'info' },
  STOCK_RESEARCH: { label: 'Stock research', tone: 'info' },
  MARKET_REPORT: { label: 'Market report', tone: 'info' },
  NAVIGATION: { label: 'Navigation', tone: 'muted' },
  PROMOTION: { label: 'Promotion', tone: 'muted' },
  OTHER: { label: 'Other', tone: 'neutral' },
  // Gmail integration and sync runs
  NOT_CONFIGURED: { label: 'Not configured', tone: 'muted' },
  CONNECTED: { label: 'Connected', tone: 'success' },
  RECONNECT_REQUIRED: { label: 'Reconnect required', tone: 'danger' },
  DISCONNECTED: { label: 'Not connected', tone: 'neutral' },
  RUNNING: { label: 'Running…', tone: 'warning' },
  SUCCEEDED: { label: 'Succeeded', tone: 'success' },
  SYNC_PARTIAL: { label: 'Partial (continues)', tone: 'warning' },
  // Research workflows and their stages (prefixed: PARTIAL/FAILED mean something else for parsing)
  WORKFLOW_NOT_STARTED: { label: 'Not started', tone: 'neutral' },
  WORKFLOW_PENDING: { label: 'Waiting', tone: 'neutral' },
  WORKFLOW_RUNNING: { label: 'Running…', tone: 'warning' },
  WORKFLOW_COMPLETED: { label: 'Completed', tone: 'success' },
  WORKFLOW_PARTIAL: { label: 'Partial', tone: 'warning' },
  WORKFLOW_FAILED: { label: 'Failed', tone: 'danger' },
  WORKFLOW_SKIPPED: { label: 'Skipped', tone: 'muted' },
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

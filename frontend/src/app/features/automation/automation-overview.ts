import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AutomationCenterApiService, GmailApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { ComponentHealth, GmailStatus, OverviewPeriod, OverviewReport, SyncRun } from '../../core/models/automation.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';

/** Reasons the OAuth callback can report (fixed codes, never Google's text). */
const CONNECT_ERRORS: Record<string, string> = {
  ACCESS_DENIED: 'Access was not granted in Google.',
  INVALID_STATE: 'The connection attempt expired or did not start in this browser. Please try again.',
  TOKEN_EXCHANGE_FAILED: 'Google did not accept the authorization. Check the OAuth client configuration.',
  SCOPE_NOT_GRANTED: 'Read-only Gmail access was not granted. Tick the Gmail permission on the consent screen.',
  NO_REFRESH_TOKEN: 'Google returned no offline access. Remove Investary at myaccount.google.com/permissions and connect again.',
  ACCOUNT_LIMIT: 'Another Gmail account is already connected. Disconnect it first.',
  NOT_CONFIGURED: 'Gmail is not configured on the server.',
  GOOGLE_ERROR: 'Google reported an error. Please try again.',
};

export const COMPONENT_LABELS: Record<ComponentHealth['component'], string> = {
  GMAIL: 'Gmail connection',
  RESEARCH_INGESTION: 'Research ingestion',
  DOCUMENT_PROCESSING: 'Document processing',
  KNOWLEDGE_INDEXING: 'Knowledge indexing',
  BRIEF_GENERATION: 'Brief generation',
};

const UNAVAILABLE_COMPONENTS = Object.keys(COMPONENT_LABELS) as ComponentHealth['component'][];

export const PERIOD_LABELS: Record<OverviewPeriod, string> = {
  '24h': 'Last 24 hours',
  '7d': 'Last 7 days',
  '30d': 'Last 30 days',
};

const POLL_MS = 2000;

/** Automation Center overview: Gmail connection and sync history, record counts for a labelled window, component health. */
@Component({
  selector: 'app-automation-overview',
  imports: [DatePipe, RouterLink, Alert, EmptyState, Pager, StatusBadge],
  templateUrl: './automation-overview.html',
})
export class AutomationOverview implements OnInit {
  private readonly gmail = inject(GmailApiService);
  private readonly center = inject(AutomationCenterApiService);
  private readonly router = inject(Router);
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  /** Query parameters set by the OAuth callback redirect. */
  readonly gmailResult = input<string | undefined>(undefined, { alias: 'gmail' });
  readonly reason = input<string | undefined>();

  protected readonly periods = Object.keys(PERIOD_LABELS) as OverviewPeriod[];
  protected readonly periodLabels = PERIOD_LABELS;
  protected readonly componentLabels = COMPONENT_LABELS;
  protected readonly status = signal<GmailStatus | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly runs = signal<SyncRun[]>([]);
  protected readonly runsPage = signal(0);
  protected readonly runsTotalPages = signal(0);
  protected readonly period = signal<OverviewPeriod>('24h');
  protected readonly overview = signal<OverviewReport | null>(null);
  protected readonly overviewLoading = signal(true);
  protected readonly overviewError = signal<string | null>(null);

  protected readonly account = computed(() => this.status()?.account ?? null);
  protected readonly connected = computed(() => this.account()?.status === 'CONNECTED');
  protected readonly running = computed(() => this.status()?.running ?? false);
  /** If the backend cannot be reached nothing is reported as operational. */
  protected readonly health = computed<ComponentHealth[]>(() => {
    const overview = this.overview();
    if (overview) {
      return overview.health;
    }
    return this.overviewError()
      ? UNAVAILABLE_COMPONENTS.map((component) => ({
          component,
          state: 'UNAVAILABLE' as const,
          reason: 'The backend could not be reached',
          lastActivityAt: null,
          checkedAt: new Date().toISOString(),
        }))
      : [];
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  ngOnInit(): void {
    const result = this.gmailResult();
    if (result === 'connected') {
      this.notice.set('Gmail connected. Run "Sync now" or wait for the next scheduled sync.');
    } else if (result === 'error') {
      this.error.set(CONNECT_ERRORS[this.reason() ?? ''] ?? CONNECT_ERRORS['GOOGLE_ERROR']);
    }
    if (result) {
      // Drop the result from the address bar so a reload does not show it again.
      void this.router.navigate([], { queryParams: {}, replaceUrl: true });
    }
    this.loadStatus();
    this.loadRuns(0);
    this.loadOverview();
  }

  protected selectPeriod(period: OverviewPeriod): void {
    this.period.set(period);
    this.loadOverview();
  }

  protected loadOverview(): void {
    this.overviewLoading.set(true);
    this.center.overview(this.period()).subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.overviewError.set(null);
        this.overviewLoading.set(false);
      },
      error: (err) => {
        this.overview.set(null);
        this.overviewError.set(errorMessage(err, 'Could not load the automation overview.'));
        this.overviewLoading.set(false);
      },
    });
  }

  protected connect(): void {
    this.busy.set(true);
    this.error.set(null);
    this.gmail.connect().subscribe({
      next: (response) => this.navigateTo(response.authorizationUrl),
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not start the Gmail connection.'));
      },
    });
  }

  protected disconnect(): void {
    if (!confirm('Disconnect Gmail? Investary revokes its access; emails already imported stay.')) {
      return;
    }
    this.busy.set(true);
    this.gmail.disconnect().subscribe({
      next: () => {
        this.busy.set(false);
        this.notice.set('Gmail disconnected.');
        this.loadStatus();
        this.loadOverview();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not disconnect Gmail.'));
      },
    });
  }

  protected syncNow(): void {
    this.busy.set(true);
    this.error.set(null);
    this.gmail.sync().subscribe({
      next: () => {
        this.busy.set(false);
        this.notice.set('Sync started.');
        this.loadStatus();
        this.loadRuns(0);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not start the sync.'));
        this.loadStatus();
      },
    });
  }

  protected loadRuns(page: number): void {
    this.gmail.history(page).subscribe({
      next: (result) => {
        this.runs.set(result.content);
        this.runsPage.set(result.page);
        this.runsTotalPages.set(result.totalPages);
      },
      error: (err) => this.error.set(errorMessage(err, 'Could not load the sync history.')),
    });
  }

  /** Test seam: leaving the app for Google's consent page. */
  protected navigateTo(url: string): void {
    window.location.assign(url);
  }

  private loadStatus(): void {
    this.gmail.status().subscribe({
      next: (status) => {
        const wasRunning = this.running();
        this.status.set(status);
        this.loading.set(false);
        if (wasRunning && !status.running) {
          this.loadRuns(0);
          this.loadOverview();
        }
        this.schedulePoll();
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the Gmail status.'));
      },
    });
  }

  private schedulePoll(): void {
    this.stopPolling();
    if (this.running()) {
      this.pollTimer = setTimeout(() => this.loadStatus(), POLL_MS);
    }
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }
}

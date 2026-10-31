import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { GmailApiService, SourceRuleApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { GmailStatus, SourceRule, SourceRuleRequest, SyncRun } from '../../core/models/automation.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';

interface RuleDraft {
  name: string;
  senderAddresses: string;
  senderDomains: string;
  subjectKeywords: string;
  gmailQuery: string;
  enabled: boolean;
  autoImport: boolean;
  autoPdfDownload: boolean;
}

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

const POLL_MS = 2000;

/**
 * Gmail integration: connection, sync status and history, and the brokerage source rules that decide
 * which verified senders are imported.
 */
@Component({
  selector: 'app-gmail-sync',
  imports: [DatePipe, FormsModule, Alert, EmptyState, Pager, StatusBadge],
  templateUrl: './gmail-sync.html',
})
export class GmailSync implements OnInit {
  private readonly gmail = inject(GmailApiService);
  private readonly rulesApi = inject(SourceRuleApiService);
  private readonly router = inject(Router);
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  /** Query parameters set by the OAuth callback redirect. */
  readonly gmailResult = input<string | undefined>(undefined, { alias: 'gmail' });
  readonly reason = input<string | undefined>();

  protected readonly status = signal<GmailStatus | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly runs = signal<SyncRun[]>([]);
  protected readonly runsPage = signal(0);
  protected readonly runsTotalPages = signal(0);
  protected readonly rules = signal<SourceRule[]>([]);
  protected readonly rulesLoading = signal(true);
  protected readonly editingRuleId = signal<string | null>(null);
  protected readonly savingRule = signal(false);
  protected draft: RuleDraft = this.emptyDraft();

  protected readonly account = computed(() => this.status()?.account ?? null);
  protected readonly connected = computed(() => this.account()?.status === 'CONNECTED');
  protected readonly running = computed(() => this.status()?.running ?? false);

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
    this.loadRules();
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

  protected saveRule(): void {
    const request = this.toRequest(this.draft);
    this.savingRule.set(true);
    this.error.set(null);
    const id = this.editingRuleId();
    (id ? this.rulesApi.update(id, request) : this.rulesApi.create(request)).subscribe({
      next: () => {
        this.savingRule.set(false);
        this.cancelRule();
        this.loadRules();
      },
      error: (err) => {
        this.savingRule.set(false);
        this.error.set(errorMessage(err, 'Could not save the source rule.'));
      },
    });
  }

  protected editRule(rule: SourceRule): void {
    this.editingRuleId.set(rule.id);
    this.draft = {
      name: rule.name,
      senderAddresses: rule.senderAddresses.join('\n'),
      senderDomains: rule.senderDomains.join('\n'),
      subjectKeywords: rule.subjectKeywords.join('\n'),
      gmailQuery: rule.gmailQuery ?? '',
      enabled: rule.enabled,
      autoImport: rule.autoImport,
      autoPdfDownload: rule.autoPdfDownload,
    };
  }

  protected cancelRule(): void {
    this.editingRuleId.set(null);
    this.draft = this.emptyDraft();
  }

  protected deleteRule(rule: SourceRule): void {
    if (!confirm(`Delete the source rule "${rule.name}"? Emails already imported stay.`)) {
      return;
    }
    this.rulesApi.delete(rule.id).subscribe({
      next: () => this.loadRules(),
      error: (err) => this.error.set(errorMessage(err, 'Could not delete the source rule.')),
    });
  }

  protected senders(rule: SourceRule): string {
    return [...rule.senderAddresses, ...rule.senderDomains.map((d) => `*@${d}`)].join(', ');
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
        }
        this.schedulePoll();
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the Gmail status.'));
      },
    });
  }

  private loadRules(): void {
    this.rulesApi.list().subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.rulesLoading.set(false);
      },
      error: (err) => {
        this.rulesLoading.set(false);
        this.error.set(errorMessage(err, 'Could not load the source rules.'));
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

  private toRequest(draft: RuleDraft): SourceRuleRequest {
    return {
      name: draft.name.trim(),
      senderAddresses: splitList(draft.senderAddresses),
      senderDomains: splitList(draft.senderDomains),
      subjectKeywords: splitList(draft.subjectKeywords),
      gmailQuery: draft.gmailQuery.trim() || null,
      enabled: draft.enabled,
      autoImport: draft.autoImport,
      autoPdfDownload: draft.autoPdfDownload,
    };
  }

  private emptyDraft(): RuleDraft {
    return {
      name: '',
      senderAddresses: '',
      senderDomains: '',
      subjectKeywords: '',
      gmailQuery: '',
      enabled: true,
      autoImport: true,
      autoPdfDownload: false,
    };
  }
}

/** One entry per line or comma; blanks dropped. */
export function splitList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((v) => v.trim())
    .filter((v) => v.length > 0);
}

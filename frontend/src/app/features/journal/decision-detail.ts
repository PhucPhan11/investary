import { DatePipe, DecimalPipe, LowerCasePipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { DocumentApiService } from '../../core/api/document-api.service';
import { EmailApiService } from '../../core/api/email-api.service';
import { errorMessage } from '../../core/api/error-message';
import { IntelligenceApiService } from '../../core/api/intelligence-api.service';
import { JournalApiService } from '../../core/api/journal-api.service';
import { PortfolioApiService } from '../../core/api/portfolio-api.service';
import {
  DECISION_TYPE_LABELS,
  DecisionAction,
  DecisionDetail as Decision,
  DecisionRevision,
  EVIDENCE_KIND_LABELS,
  EVIDENCE_KINDS,
  EVIDENCE_ROLE_LABELS,
  EVIDENCE_ROLES,
  EvidenceKind,
  EvidenceRole,
  REVISION_LABELS,
} from '../../core/models/journal.model';
import { Transaction } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';
import { StatusBadge } from '../../shared/components/status-badge';

interface SourceOption {
  id: string;
  label: string;
}

export interface ChangeRow {
  field: string;
  from?: string;
  to?: string;
  value?: string;
}

/** Turns a revision's JSON changes into display rows: `{field: {from, to}}` diffs or plain values. */
export function changeRows(changes: Record<string, unknown>): ChangeRow[] {
  return Object.entries(changes ?? {}).map(([field, value]) => {
    if (value && typeof value === 'object' && ('from' in value || 'to' in value)) {
      const change = value as { from?: unknown; to?: unknown };
      return { field, from: display(change.from), to: display(change.to) };
    }
    return { field, value: display(value) };
  });
}

function display(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/**
 * One decision: its reasoning, status actions, the snapshot stored at finalization, evidence, linked portfolio
 * transactions and the revision history.
 */
@Component({
  selector: 'app-decision-detail',
  imports: [DatePipe, DecimalPipe, LowerCasePipe, FormsModule, RouterLink, Alert, StatusBadge],
  templateUrl: './decision-detail.html',
})
export class DecisionDetail implements OnInit {
  private readonly api = inject(JournalApiService);
  private readonly portfolioApi = inject(PortfolioApiService);
  private readonly documentApi = inject(DocumentApiService);
  private readonly emailApi = inject(EmailApiService);
  private readonly intelligenceApi = inject(IntelligenceApiService);

  readonly id = input.required<string>();

  protected readonly typeLabels = DECISION_TYPE_LABELS;
  protected readonly kindLabels = EVIDENCE_KIND_LABELS;
  protected readonly roleLabels = EVIDENCE_ROLE_LABELS;
  protected readonly revisionLabels = REVISION_LABELS;
  protected readonly kinds = EVIDENCE_KINDS;
  protected readonly roles = EVIDENCE_ROLES;
  protected readonly changeRows = changeRows;

  protected readonly decision = signal<Decision | null>(null);
  protected readonly history = signal<DecisionRevision[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly sources = signal<SourceOption[]>([]);
  protected readonly researchEmails = signal<SourceOption[]>([]);
  protected readonly transactions = signal<Transaction[]>([]);
  protected readonly showEvidenceForm = signal(false);
  protected readonly showLinkForm = signal(false);

  protected readonly can = computed(() => new Set<DecisionAction>(this.decision()?.actions ?? []));
  protected readonly activeEvidence = computed(() => (this.decision()?.evidence ?? []).filter((e) => !e.removedAt));
  protected readonly removedEvidence = computed(() => (this.decision()?.evidence ?? []).filter((e) => e.removedAt));
  protected readonly linkableTransactions = computed(() => {
    const linked = new Set((this.decision()?.transactions ?? []).filter((l) => !l.removedAt).map((l) => l.transactionId));
    return this.transactions().filter((t) => !linked.has(t.id));
  });

  protected reason = '';
  protected evidenceForm: { kind: EvidenceKind; role: EvidenceRole; sourceId: string; emailId: string; note: string } = {
    kind: 'DOCUMENT',
    role: 'SUPPORTING',
    sourceId: '',
    emailId: '',
    note: '',
  };
  protected linkForm = { transactionId: '', note: '' };

  ngOnInit(): void {
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.get(this.id()).subscribe({
      next: (decision) => {
        this.decision.set(decision);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load the decision.'));
        this.loading.set(false);
      },
    });
    this.loadHistory();
  }

  protected finalize(): void {
    const d = this.decision();
    if (d) {
      this.run(this.api.finalize(d.id, d.version));
    }
  }

  protected execute(): void {
    const d = this.decision();
    if (d) {
      this.run(this.api.execute(d.id, d.version, this.reason));
    }
  }

  protected cancel(): void {
    const d = this.decision();
    if (!d) {
      return;
    }
    if (!this.reason.trim()) {
      this.actionError.set('Give a reason to cancel the decision.');
      return;
    }
    this.run(this.api.cancel(d.id, d.version, this.reason.trim()));
  }

  protected openEvidenceForm(): void {
    this.showEvidenceForm.set(true);
    this.loadSources();
  }

  protected loadSources(): void {
    this.sources.set([]);
    this.evidenceForm.sourceId = '';
    const kind = this.evidenceForm.kind;
    if (kind === 'DOCUMENT') {
      this.documentApi.list(0, 50).subscribe({
        next: (page) => this.sources.set(page.content.map((d) => ({ id: d.id, label: d.originalFilename }))),
        error: () => this.sources.set([]),
      });
    } else if (kind === 'EMAIL' || kind === 'RESEARCH_ITEM') {
      this.emailApi.list({ size: 50 }).subscribe({
        next: (page) => {
          const options = page.content.map((e) => ({ id: e.id, label: `${e.subject ?? '(no subject)'} · ${e.senderName ?? ''}` }));
          if (kind === 'EMAIL') {
            this.sources.set(options);
          } else {
            this.researchEmails.set(options);
          }
        },
        error: () => this.sources.set([]),
      });
    } else if (kind === 'IMPACT_ASSESSMENT') {
      const portfolioId = this.decision()?.portfolioId;
      if (portfolioId) {
        this.intelligenceApi.assessments(portfolioId).subscribe({
          next: (items) => this.sources.set(items.map((a) => ({ id: a.id, label: `Assessment of ${a.createdAt}` }))),
          error: () => this.sources.set([]),
        });
      }
    }
  }

  protected loadResearchItems(): void {
    this.sources.set([]);
    this.evidenceForm.sourceId = '';
    if (!this.evidenceForm.emailId) {
      return;
    }
    this.emailApi.researchItems(this.evidenceForm.emailId).subscribe({
      next: (items) => this.sources.set(items.map((i) => ({ id: i.id, label: `${i.title ?? i.itemType}${i.ticker ? ' · ' + i.ticker : ''}` }))),
      error: () => this.sources.set([]),
    });
  }

  protected attachEvidence(): void {
    const d = this.decision();
    const sourceId = this.evidenceForm.sourceId.trim();
    if (!d) {
      return;
    }
    if (!sourceId) {
      this.actionError.set('Choose a source or enter its id.');
      return;
    }
    this.run(
      this.api.attachEvidence(d.id, {
        kind: this.evidenceForm.kind,
        role: this.evidenceForm.role,
        sourceId,
        note: this.evidenceForm.note.trim() || null,
        expectedVersion: d.version,
      }),
      () => {
        this.showEvidenceForm.set(false);
        this.evidenceForm.note = '';
      },
    );
  }

  protected removeEvidence(evidenceId: string): void {
    const d = this.decision();
    const reason = typeof window !== 'undefined' ? window.prompt('Why are you removing this evidence?') : null;
    if (d && reason?.trim()) {
      this.run(this.api.removeEvidence(d.id, evidenceId, d.version, reason.trim()));
    }
  }

  protected openLinkForm(): void {
    const portfolioId = this.decision()?.portfolioId;
    this.showLinkForm.set(true);
    if (portfolioId) {
      this.portfolioApi.transactions(portfolioId, 0, 100).subscribe({
        next: (page) => this.transactions.set(page.content),
        error: (err) => this.actionError.set(errorMessage(err, 'Could not load the portfolio transactions.')),
      });
    }
  }

  protected linkTransaction(): void {
    const d = this.decision();
    if (!d || !this.linkForm.transactionId) {
      this.actionError.set('Choose a transaction to link.');
      return;
    }
    this.run(this.api.linkTransaction(d.id, this.linkForm.transactionId, d.version, this.linkForm.note.trim() || null), () => {
      this.showLinkForm.set(false);
      this.linkForm = { transactionId: '', note: '' };
    });
  }

  protected unlink(linkId: string): void {
    const d = this.decision();
    const reason = typeof window !== 'undefined' ? window.prompt('Why are you unlinking this transaction?') : null;
    if (d && reason?.trim()) {
      this.run(this.api.unlinkTransaction(d.id, linkId, d.version, reason.trim()));
    }
  }

  protected statusBadge(status: string): string {
    return 'DECISION_' + status;
  }

  private run(call: ReturnType<JournalApiService['get']>, after?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    call.subscribe({
      next: (decision) => {
        this.decision.set(decision);
        this.busy.set(false);
        this.reason = '';
        after?.();
        this.loadHistory();
      },
      error: (err) => {
        this.busy.set(false);
        this.actionError.set(errorMessage(err, 'The change could not be saved.'));
      },
    });
  }

  private loadHistory(): void {
    this.api.history(this.id()).subscribe({
      next: (revisions) => this.history.set(revisions),
      error: () => this.history.set([]),
    });
  }
}

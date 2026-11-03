import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/error-message';
import { JournalApiService } from '../../core/api/journal-api.service';
import { InstrumentApiService, PortfolioApiService } from '../../core/api/portfolio-api.service';
import {
  DECISION_TYPE_LABELS,
  DECISION_TYPES,
  DecisionDetail,
  DecisionRequest,
  DecisionType,
} from '../../core/models/journal.model';
import { Instrument, Portfolio } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';

interface FormModel {
  portfolioId: string;
  instrumentId: string;
  decisionType: DecisionType;
  decisionDate: string;
  title: string;
  rationale: string;
  expectedOutcome: string;
  timeHorizon: string;
  mainAssumptions: string;
  risks: string;
  invalidationConditions: string;
  reviewDate: string;
  notes: string;
  intendedQuantity: number | null;
  intendedAllocationPct: number | null;
  intendedPrice: number | null;
  amendmentReason: string;
}

function emptyModel(): FormModel {
  return {
    portfolioId: '',
    instrumentId: '',
    decisionType: 'BUY',
    decisionDate: '',
    title: '',
    rationale: '',
    expectedOutcome: '',
    timeHorizon: '',
    mainAssumptions: '',
    risks: '',
    invalidationConditions: '',
    reviewDate: '',
    notes: '',
    intendedQuantity: null,
    intendedAllocationPct: null,
    intendedPrice: null,
    amendmentReason: '',
  };
}

function orNull(value: string): string | null {
  return value.trim() ? value.trim() : null;
}

/**
 * Creates a decision, edits a draft, or amends a finalized decision. After finalization the type, portfolio,
 * instrument, date and intended figures are read-only and every change needs a reason.
 */
@Component({
  selector: 'app-decision-form',
  imports: [FormsModule, RouterLink, Alert],
  templateUrl: './decision-form.html',
})
export class DecisionForm implements OnInit {
  private readonly api = inject(JournalApiService);
  private readonly portfolioApi = inject(PortfolioApiService);
  private readonly instrumentApi = inject(InstrumentApiService);
  private readonly router = inject(Router);

  /** Set on `/journal/:id/edit`; absent on `/journal/new`. */
  readonly id = input<string>();

  protected readonly types = DECISION_TYPES;
  protected readonly typeLabels = DECISION_TYPE_LABELS;
  protected readonly portfolios = signal<Portfolio[]>([]);
  protected readonly instruments = signal<Instrument[]>([]);
  protected readonly existing = signal<DecisionDetail | null>(null);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Finalized decisions are amended: locked fields are read-only and a reason is required. */
  protected readonly amending = computed(() => {
    const decision = this.existing();
    return decision !== null && decision.status !== 'DRAFT';
  });

  protected model: FormModel = emptyModel();

  ngOnInit(): void {
    this.portfolioApi.list().subscribe({ next: (p) => this.portfolios.set(p), error: () => this.portfolios.set([]) });
    this.instrumentApi.list().subscribe({ next: (i) => this.instruments.set(i), error: () => this.instruments.set([]) });
    const id = this.id();
    if (id) {
      this.loading.set(true);
      this.api.get(id).subscribe({
        next: (decision) => {
          this.model = this.fromDecision(decision);
          this.existing.set(decision);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(errorMessage(err, 'Could not load the decision.'));
          this.loading.set(false);
        },
      });
    }
  }

  protected save(): void {
    const decision = this.existing();
    if (!this.model.title.trim()) {
      this.error.set('Give the decision a title.');
      return;
    }
    if (this.amending() && !this.model.amendmentReason.trim()) {
      this.error.set('Explain why you are amending a finalized decision.');
      return;
    }
    const request = this.toRequest();
    this.saving.set(true);
    const call = decision ? this.api.update(decision.id, request) : this.api.create(request);
    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        void this.router.navigate(['/journal', saved.id]);
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'The decision could not be saved.'));
      },
    });
  }

  private toRequest(): DecisionRequest {
    const m = this.model;
    return {
      portfolioId: m.portfolioId || null,
      instrumentId: m.instrumentId || null,
      decisionType: m.decisionType,
      decisionDate: m.decisionDate || null,
      title: m.title.trim(),
      rationale: orNull(m.rationale),
      expectedOutcome: orNull(m.expectedOutcome),
      timeHorizon: orNull(m.timeHorizon),
      mainAssumptions: orNull(m.mainAssumptions),
      risks: orNull(m.risks),
      invalidationConditions: orNull(m.invalidationConditions),
      reviewDate: m.reviewDate || null,
      notes: orNull(m.notes),
      intendedQuantity: m.intendedQuantity ?? null,
      intendedAllocationPct: m.intendedAllocationPct ?? null,
      intendedPrice: m.intendedPrice ?? null,
      expectedVersion: this.existing()?.version ?? null,
      amendmentReason: orNull(m.amendmentReason),
    };
  }

  private fromDecision(d: DecisionDetail): FormModel {
    return {
      portfolioId: d.portfolioId ?? '',
      instrumentId: d.instrumentId ?? '',
      decisionType: d.decisionType,
      decisionDate: d.decisionDate ?? '',
      title: d.title,
      rationale: d.rationale ?? '',
      expectedOutcome: d.expectedOutcome ?? '',
      timeHorizon: d.timeHorizon ?? '',
      mainAssumptions: d.mainAssumptions ?? '',
      risks: d.risks ?? '',
      invalidationConditions: d.invalidationConditions ?? '',
      reviewDate: d.reviewDate ?? '',
      notes: d.notes ?? '',
      intendedQuantity: d.intendedQuantity,
      intendedAllocationPct: d.intendedAllocationPct,
      intendedPrice: d.intendedPrice,
      amendmentReason: '',
    };
  }
}

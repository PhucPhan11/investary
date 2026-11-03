import { DatePipe } from '@angular/common';
import { Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/error-message';
import { IntelligenceApiService } from '../../core/api/intelligence-api.service';
import {
  Assessment,
  AssessmentListItem,
  EvidenceReference,
  ImpactCategory,
  RelevantResearch,
} from '../../core/models/intelligence.model';
import { Alert } from '../../shared/components/alert';

export const CATEGORIES: Record<ImpactCategory, { label: string; tone: string }> = {
  DIRECT_HOLDING: { label: 'Direct research', tone: 'info' },
  MARKET_CONTEXT: { label: 'Market context only', tone: 'neutral' },
  POTENTIAL_RISK: { label: 'Potential risk', tone: 'danger' },
  POTENTIAL_OPPORTUNITY: { label: 'Potential opportunity', tone: 'success' },
  MONITORING_ONLY: { label: 'Monitoring only', tone: 'muted' },
  INSUFFICIENT_EVIDENCE: { label: 'Insufficient evidence', tone: 'warning' },
};

/**
 * Research matched to the portfolio's holdings and saved impact assessments. Evidence links to the
 * stored document or email; AI text is shown as plain text and labelled as interpretation.
 */
@Component({
  selector: 'app-research-insights',
  imports: [DatePipe, RouterLink, Alert],
  templateUrl: './research-insights.html',
})
export class ResearchInsights {
  private readonly api = inject(IntelligenceApiService);

  readonly portfolioId = input.required<string>();
  /** Changes whenever holdings change, so the assessment list refreshes its stale flags. */
  readonly revision = input(0);

  protected readonly research = signal<RelevantResearch | null>(null);
  protected readonly assessments = signal<AssessmentListItem[]>([]);
  protected readonly selected = signal<Assessment | null>(null);
  protected readonly busy = signal<'research' | 'assessment' | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.revision();
      this.loadAssessments(this.portfolioId());
    });
  }

  protected findResearch(): void {
    this.busy.set('research');
    this.error.set(null);
    this.api.relevantResearch(this.portfolioId()).subscribe({
      next: (research) => {
        this.research.set(research);
        this.busy.set(null);
      },
      error: (err) => {
        this.busy.set(null);
        this.error.set(errorMessage(err, 'Could not match research to this portfolio.'));
      },
    });
  }

  protected runAssessment(): void {
    this.busy.set('assessment');
    this.error.set(null);
    this.api.createAssessment(this.portfolioId()).subscribe({
      next: (assessment) => {
        this.selected.set(assessment);
        this.busy.set(null);
        this.loadAssessments(this.portfolioId());
      },
      error: (err) => {
        this.busy.set(null);
        this.error.set(errorMessage(err, 'Could not create the impact assessment.'));
      },
    });
  }

  protected open(id: string): void {
    this.api.assessment(id).subscribe({
      next: (assessment) => this.selected.set(assessment),
      error: (err) => this.error.set(errorMessage(err, 'Could not load the assessment.')),
    });
  }

  protected category(value: ImpactCategory) {
    return CATEGORIES[value];
  }

  protected link(evidence: EvidenceReference): unknown[] {
    return [evidence.sourceType === 'DOCUMENT' ? '/documents' : '/emails', evidence.sourceId];
  }

  protected staleText(reasons: string[]): string {
    return reasons
      .map((r) => (r === 'HOLDINGS_CHANGED' ? 'holdings changed' : r === 'EVIDENCE_CHANGED' ? 'evidence changed' : r))
      .join(', ');
  }

  private loadAssessments(portfolioId: string): void {
    this.api.assessments(portfolioId).subscribe({
      next: (list) => this.assessments.set(list),
      error: (err) => this.error.set(errorMessage(err, 'Could not load saved assessments.')),
    });
  }
}

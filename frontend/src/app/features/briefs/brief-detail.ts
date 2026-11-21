import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { BriefApiService } from '../../core/api/brief-api.service';
import { errorMessage } from '../../core/api/error-message';
import { Brief, BriefCitation, BriefItem, BriefItemKind, BriefJob, BriefSourceRef } from '../../core/models/brief.model';
import { EvidenceStatus } from '../../core/models/knowledge.model';
import { Alert } from '../../shared/components/alert';
import { StatusBadge } from '../../shared/components/status-badge';
import { AnswerSegment, answerSegments } from '../assistant/answer-segments';
import { RESEARCH_STATE_LABELS } from './brief-list';

const POLL_MS = 2000;

const KINDS: Record<BriefItemKind, { label: string; tone: string }> = {
  PORTFOLIO_FACT: { label: $localize`:@@briefDetail.kinds.PORTFOLIO_FACT:Investary data`, tone: 'info' },
  SOURCE_CLAIM: { label: $localize`:@@briefDetail.kinds.SOURCE_CLAIM:From source`, tone: 'neutral' },
  AI_INTERPRETATION: { label: $localize`:@@briefDetail.kinds.AI_INTERPRETATION:AI interpretation`, tone: 'warning' },
  SYSTEM_NOTE: { label: $localize`:@@briefDetail.kinds.SYSTEM_NOTE:Note`, tone: 'muted' },
};

const EVIDENCE: Record<EvidenceStatus, { label: string; tone: string }> = {
  SUPPORTED: { label: $localize`:@@briefDetail.evidence.SUPPORTED:Supported`, tone: 'success' },
  PARTIAL: { label: $localize`:@@briefDetail.evidence.PARTIAL:Partially supported`, tone: 'warning' },
  INSUFFICIENT: { label: $localize`:@@briefDetail.evidence.INSUFFICIENT:Insufficient evidence`, tone: 'danger' },
};

const PENDING_STATES: Record<string, string> = {
  NOT_TRACKED: $localize`:@@briefDetail.pending.waiting:waiting to be indexed`,
  PENDING: $localize`:@@briefDetail.pending.waiting:waiting to be indexed`,
  PROCESSING: $localize`:@@briefDetail.pending.PROCESSING:being indexed`,
  FAILED: $localize`:@@briefDetail.pending.FAILED:indexing failed`,
  SKIPPED: $localize`:@@briefDetail.pending.SKIPPED:no extractable text`,
  OUTDATED: $localize`:@@briefDetail.pending.OUTDATED:being re-indexed`,
};

/**
 * One brief: sections as plain text with citation buttons (never HTML), the citation panel, sources with
 * their four dates, limitations, revisions and regeneration.
 */
@Component({
  selector: 'app-brief-detail',
  imports: [DatePipe, RouterLink, Alert, StatusBadge],
  templateUrl: './brief-detail.html',
})
export class BriefDetail {
  private readonly api = inject(BriefApiService);
  private readonly router = inject(Router);
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  readonly id = input.required<string>();

  protected readonly kinds = KINDS;
  protected readonly generalBrief = $localize`:@@briefs.generalBrief:General brief`;
  protected readonly noTitle = $localize`:@@briefDetail.noTitle:(no title)`;
  protected readonly deletedSource = $localize`:@@briefDetail.deletedSource:(deleted source)`;
  protected readonly stateLabels = RESEARCH_STATE_LABELS;
  protected readonly brief = signal<Brief | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly job = signal<BriefJob | null>(null);
  protected readonly regenerating = signal(false);
  protected readonly regenerateError = signal<string | null>(null);

  /** How fresh the cited research is: publication range and old or late sources, from the stored citations. */
  protected readonly freshness = computed(() => {
    const citations = this.brief()?.citations ?? [];
    const dates = citations.map((c) => c.publishedOn).filter((d): d is string => !!d).sort();
    return {
      sources: new Set(citations.map((c) => c.sourceId ?? c.label)).size,
      newest: dates.at(-1) ?? null,
      oldest: dates[0] ?? null,
      undated: citations.filter((c) => !c.publishedOn).length,
      old: citations.filter((c) => c.oldPublication).length,
      late: citations.filter((c) => c.lateArrival).length,
    };
  });

  protected readonly selectedCitation = computed(() => {
    const label = this.selected();
    return this.brief()?.citations.find((c) => c.label === label) ?? null;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
    effect(() => this.load(this.id()));
  }

  private load(id: string): void {
    this.loading.set(true);
    this.selected.set(null);
    this.api.get(id).subscribe({
      next: (brief) => {
        this.brief.set(brief);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, $localize`:@@briefDetail.error.loadBrief:Could not load the brief.`));
        this.loading.set(false);
      },
    });
  }

  protected showSourceLabel(label: string): string {
    return $localize`:@@researchAssistant.showSource:Show source ${label}:label:`;
  }

  protected segments(item: BriefItem): AnswerSegment[] {
    return answerSegments(item.text);
  }

  protected evidence(status: EvidenceStatus | null): { label: string; tone: string } | null {
    return status ? EVIDENCE[status] : null;
  }

  /** Selects a citation and brings its source into view in the source panel. */
  protected show(label: string): void {
    this.selected.set(label);
    setTimeout(() => document.getElementById('source-' + label)?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }));
  }

  protected sourceLink(source: BriefCitation | BriefSourceRef): string[] | null {
    if (!source.sourceId) {
      return null;
    }
    return [source.sourceType === 'DOCUMENT' ? '/documents' : '/emails', source.sourceId];
  }

  protected pendingState(state: string | null): string {
    return state ? (PENDING_STATES[state] ?? state) : '';
  }

  protected regenerate(): void {
    const brief = this.brief();
    if (!brief) {
      return;
    }
    this.regenerating.set(true);
    this.regenerateError.set(null);
    this.api.regenerate(brief.id).subscribe({
      next: (job) => {
        this.job.set(job);
        this.poll(job.id);
      },
      error: (err) => {
        this.regenerating.set(false);
        this.regenerateError.set(errorMessage(err, $localize`:@@briefDetail.error.startRegeneration:Could not start the regeneration.`));
      },
    });
  }

  private poll(id: string): void {
    this.stopPolling();
    this.pollTimer = setTimeout(() => {
      this.api.job(id).subscribe({
        next: (job) => {
          this.job.set(job);
          if (job.status === 'PENDING' || job.status === 'RUNNING') {
            this.poll(id);
            return;
          }
          this.regenerating.set(false);
          if (job.briefId && job.briefId !== this.id()) {
            this.router.navigate(['/briefs', job.briefId]);
          }
        },
        error: (err) => {
          this.regenerating.set(false);
          this.regenerateError.set(errorMessage(err, $localize`:@@briefDetail.error.readRegenerationStatus:Could not read the regeneration status.`));
        },
      });
    }, POLL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer !== null) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }
}

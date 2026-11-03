import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AutomationCenterApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { AiUsage, UsageBucket } from '../../core/models/automation.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';

export const PURPOSE_LABELS: Record<string, string> = {
  ASSESSMENT: 'Impact assessments',
  BRIEF: 'Daily briefs',
  RESEARCH_ASSISTANT: 'Research assistant',
  PORTFOLIO_ASSISTANT: 'Portfolio assistant',
  INDEXING: 'Indexing',
  SEARCH: 'Search queries',
  OTHER: 'Other',
};

/** AI usage: provider requests, failures and provider-reported tokens; limits against today's use. No costs are invented. */
@Component({
  selector: 'app-ai-usage',
  imports: [DatePipe, DecimalPipe, RouterLink, Alert, EmptyState],
  templateUrl: './ai-usage.html',
})
export class AiUsagePage implements OnInit {
  private readonly api = inject(AutomationCenterApiService);

  protected readonly period = signal<'day' | 'week'>('day');
  protected readonly usage = signal<AiUsage | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly purposeLabels = PURPOSE_LABELS;

  /** Largest request count of a bucket, to scale the bars. */
  protected readonly maxRequests = computed(() =>
    Math.max(1, ...(this.usage()?.buckets ?? []).map((b) => b.chatRequests + b.embeddingRequests)),
  );
  protected readonly totals = computed(() => {
    const buckets = this.usage()?.buckets ?? [];
    const sum = (pick: (b: UsageBucket) => number) => buckets.reduce((total, b) => total + pick(b), 0);
    return {
      chatRequests: sum((b) => b.chatRequests),
      chatFailures: sum((b) => b.chatFailures),
      embeddingRequests: sum((b) => b.embeddingRequests),
      embeddingFailures: sum((b) => b.embeddingFailures),
      tokens: sum((b) => b.inputTokens + b.outputTokens),
      briefs: sum((b) => b.briefGenerations),
      analyses: sum((b) => b.analyses),
    };
  });

  ngOnInit(): void {
    this.load();
  }

  protected select(period: 'day' | 'week'): void {
    this.period.set(period);
    this.load();
  }

  protected percent(used: number, limit: number | null): number {
    if (!limit) {
      return 0;
    }
    return Math.min(100, Math.round((used / limit) * 100));
  }

  protected width(bucket: UsageBucket): string {
    return `${Math.round(((bucket.chatRequests + bucket.embeddingRequests) / this.maxRequests()) * 100)}%`;
  }

  private load(): void {
    this.loading.set(true);
    const days = this.period() === 'day' ? 14 : 56;
    this.api.usage(this.period(), days).subscribe({
      next: (usage) => {
        this.usage.set(usage);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the AI usage.'));
      },
    });
  }
}

import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { BriefApiService } from '../../core/api/brief-api.service';
import { errorMessage } from '../../core/api/error-message';
import { PortfolioApiService } from '../../core/api/portfolio-api.service';
import { BriefJob, BriefListItem, ResearchState } from '../../core/models/brief.model';
import { Page } from '../../core/models/document.model';
import { Portfolio } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';

const POLL_MS = 2000;

export const RESEARCH_STATE_LABELS: Record<ResearchState, string> = {
  NEW_RESEARCH: 'New research',
  NO_MATERIAL_RESEARCH: 'No material new research',
  PENDING_ONLY: 'Research still processing',
};

/** Daily briefs: list with filters, and manual generation that polls the generation job. */
@Component({
  selector: 'app-brief-list',
  imports: [DatePipe, FormsModule, RouterLink, Alert, EmptyState, Pager, StatusBadge],
  templateUrl: './brief-list.html',
})
export class BriefList {
  private readonly api = inject(BriefApiService);
  private readonly portfolioApi = inject(PortfolioApiService);
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly stateLabels = RESEARCH_STATE_LABELS;
  protected readonly portfolios = signal<Portfolio[]>([]);
  protected readonly page = signal<Page<BriefListItem> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly job = signal<BriefJob | null>(null);
  protected readonly generating = signal(false);
  protected readonly generateError = signal<string | null>(null);

  protected filters = { portfolioId: '', from: '', to: '' };
  protected request = { portfolioId: '', date: '' };
  private pageNumber = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
    this.portfolioApi.list().subscribe({
      next: (portfolios) => {
        this.portfolios.set(portfolios);
        if (portfolios.length === 1) {
          this.request.portfolioId = portfolios[0].id;
        }
      },
      error: () => this.portfolios.set([]),
    });
    this.load(0);
  }

  protected load(page = this.pageNumber): void {
    this.pageNumber = page;
    this.loading.set(true);
    this.api.list(this.filters, page).subscribe({
      next: (result) => {
        this.page.set(result);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load the briefs.'));
        this.loading.set(false);
      },
    });
  }

  protected applyFilters(): void {
    this.load(0);
  }

  protected generate(): void {
    this.generating.set(true);
    this.generateError.set(null);
    this.job.set(null);
    this.api.generate(this.request.portfolioId || undefined, this.request.date || undefined).subscribe({
      next: (job) => {
        this.job.set(job);
        this.poll(job.id);
      },
      error: (err) => {
        this.generating.set(false);
        this.generateError.set(errorMessage(err, 'Could not start the brief.'));
      },
    });
  }

  private poll(id: string): void {
    this.stopPolling();
    this.pollTimer = setTimeout(() => {
      this.api.job(id).subscribe({
        next: (job) => {
          this.job.set(job);
          if (job.status === 'SUCCEEDED' || job.status === 'FAILED') {
            this.generating.set(false);
            this.load(0);
          } else {
            this.poll(id);
          }
        },
        error: (err) => {
          this.generating.set(false);
          this.generateError.set(errorMessage(err, 'Could not read the generation status.'));
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

  protected scope(item: BriefListItem): string {
    return item.portfolioName ?? (item.portfolioId ? 'Portfolio' : 'General brief');
  }
}

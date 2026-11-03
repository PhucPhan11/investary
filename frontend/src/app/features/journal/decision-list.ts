import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/error-message';
import { JournalApiService } from '../../core/api/journal-api.service';
import { PortfolioApiService } from '../../core/api/portfolio-api.service';
import { Page } from '../../core/models/document.model';
import {
  DECISION_STATUSES,
  DECISION_TYPE_LABELS,
  DECISION_TYPES,
  DecisionFilters,
  DecisionSummary,
} from '../../core/models/journal.model';
import { Portfolio } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';

/** The decision journal: filterable, paged list of decisions. */
@Component({
  selector: 'app-decision-list',
  imports: [FormsModule, RouterLink, Alert, EmptyState, Pager, StatusBadge],
  templateUrl: './decision-list.html',
})
export class DecisionList {
  private readonly api = inject(JournalApiService);
  private readonly portfolioApi = inject(PortfolioApiService);

  protected readonly typeLabels = DECISION_TYPE_LABELS;
  protected readonly types = DECISION_TYPES;
  protected readonly statuses = DECISION_STATUSES;
  protected readonly portfolios = signal<Portfolio[]>([]);
  protected readonly page = signal<Page<DecisionSummary> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected filters: Required<Pick<DecisionFilters, 'ticker' | 'portfolioId' | 'status' | 'type'>> = {
    ticker: '',
    portfolioId: '',
    status: '',
    type: '',
  };
  private pageNumber = 0;

  constructor() {
    this.portfolioApi.list().subscribe({
      next: (portfolios) => this.portfolios.set(portfolios),
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
        this.error.set(errorMessage(err, 'Could not load the decision journal.'));
        this.loading.set(false);
      },
    });
  }

  protected applyFilters(): void {
    this.load(0);
  }

  protected clearFilters(): void {
    this.filters = { ticker: '', portfolioId: '', status: '', type: '' };
    this.load(0);
  }

  protected statusBadge(status: string): string {
    return 'DECISION_' + status;
  }
}

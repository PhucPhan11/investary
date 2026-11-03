import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/error-message';
import { PortfolioApiService } from '../../core/api/portfolio-api.service';
import { Portfolio } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';

/** All portfolios, plus a form to create one. */
@Component({
  selector: 'app-portfolio-list',
  imports: [DatePipe, FormsModule, RouterLink, Alert, EmptyState],
  template: `
    <header class="page-header">
      <div>
        <h1 class="page-title">Portfolios</h1>
        <p class="page-subtitle">
          Holdings are computed from the transactions you enter. Prices are manual observations; Investary has no live
          market data and gives no trading advice.
        </p>
      </div>
    </header>

    @if (error(); as message) {
      <app-alert kind="error">{{ message }}</app-alert>
    }

    <form class="card form-grid" (ngSubmit)="create()">
      <h2 class="card__title">New portfolio</h2>
      <label class="field">
        <span class="field__label">Name</span>
        <input class="input" name="name" maxlength="200" required [(ngModel)]="name" />
      </label>
      <label class="field">
        <span class="field__label">Base currency</span>
        <input class="input" name="currency" maxlength="3" [(ngModel)]="currency" />
      </label>
      <label class="field field--wide">
        <span class="field__label">Description (optional)</span>
        <input class="input" name="description" maxlength="2000" [(ngModel)]="description" />
      </label>
      <div class="actions">
        <button type="submit" class="btn btn--primary" [disabled]="saving() || !name.trim()">
          {{ saving() ? 'Creating…' : 'Create portfolio' }}
        </button>
      </div>
    </form>

    @if (loading()) {
      <p class="muted" role="status">Loading portfolios…</p>
    } @else if (portfolios().length === 0) {
      <app-empty-state title="No portfolios yet">Create a portfolio, then add opening positions or trades.</app-empty-state>
    } @else {
      <div class="card">
        <table class="table">
          <thead>
            <tr><th>Name</th><th>Currency</th><th class="num">Transactions</th><th>Updated</th></tr>
          </thead>
          <tbody>
            @for (p of portfolios(); track p.id) {
              <tr>
                <td><a class="link" [routerLink]="['/portfolios', p.id]">{{ p.name }}</a></td>
                <td>{{ p.baseCurrency }}</td>
                <td class="num">{{ p.transactionCount }}</td>
                <td>{{ p.updatedAt | date: 'medium' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class PortfolioList {
  private readonly api = inject(PortfolioApiService);

  protected readonly portfolios = signal<Portfolio[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected name = '';
  protected currency = 'VND';
  protected description = '';

  constructor() {
    this.load();
  }

  protected create(): void {
    if (!this.name.trim() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.create({ name: this.name.trim(), baseCurrency: this.currency.trim() || 'VND', description: this.description.trim() || null }).subscribe({
      next: () => {
        this.saving.set(false);
        this.name = '';
        this.description = '';
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'Could not create the portfolio.'));
      },
    });
  }

  private load(): void {
    this.api.list().subscribe({
      next: (list) => {
        this.portfolios.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load portfolios.'));
      },
    });
  }
}

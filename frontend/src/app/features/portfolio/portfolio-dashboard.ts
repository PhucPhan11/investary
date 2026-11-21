import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { errorMessage } from '../../core/api/error-message';
import { InstrumentApiService, PortfolioApiService } from '../../core/api/portfolio-api.service';
import {
  Allocation,
  AllocationBreakdown,
  Holding,
  Instrument,
  Portfolio,
  PortfolioSummary,
  Transaction,
  ValuationStatus,
} from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { ResearchInsights } from './research-insights';
import { TransactionForm, marketToday } from './transaction-form';

export const VALUATION: Record<ValuationStatus, { label: string; tone: string; text: string }> = {
  AVAILABLE: { label: $localize`:@@portfolioDashboard.valuation.AVAILABLE:Valued`, tone: 'success', text: $localize`:@@portfolioDashboard.everyHoldingHasRecent:Every holding has a recent manual price.` },
  STALE: { label: $localize`:@@portfolioDashboard.valuation.STALE:Prices stale`, tone: 'warning', text: $localize`:@@portfolioDashboard.everyHoldingPricedBut:Every holding is priced, but some prices are old.` },
  PARTIAL: { label: $localize`:@@portfolioDashboard.valuation.PARTIAL:Partially valued`, tone: 'warning', text: $localize`:@@portfolioDashboard.someHoldingsHaveNo:Some holdings have no price; totals cover priced holdings only.` },
  UNAVAILABLE: { label: $localize`:@@portfolioDashboard.valuation.UNAVAILABLE:No prices`, tone: 'neutral', text: $localize`:@@portfolioDashboard.noPriceObservationsYet:No price observations yet; only cost-basis figures are shown.` },
};

/**
 * One portfolio: overview, allocation, holdings, transactions and research insights. Market values and
 * unrealized P&L are shown only where the backend has a real price observation.
 */
@Component({
  selector: 'app-portfolio-dashboard',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink, Alert, EmptyState, TransactionForm, ResearchInsights],
  templateUrl: './portfolio-dashboard.html',
})
export class PortfolioDashboard {
  protected readonly defaultDescription = $localize`:@@portfolioDashboard.defaultDescription:Holdings computed from your transactions (weighted average cost).`;
  private readonly api = inject(PortfolioApiService);
  private readonly instrumentApi = inject(InstrumentApiService);

  readonly id = input.required<string>();

  protected readonly portfolio = signal<Portfolio | null>(null);
  protected readonly summary = signal<PortfolioSummary | null>(null);
  protected readonly holdings = signal<Holding[]>([]);
  protected readonly allocation = signal<Allocation | null>(null);
  protected readonly transactions = signal<Transaction[]>([]);
  protected readonly instruments = signal<Instrument[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly editing = signal<Transaction | null>(null);
  protected readonly pricing = signal<Holding | null>(null);
  protected readonly revision = signal(0);
  protected readonly valuation = computed(() => {
    const s = this.summary();
    return s ? VALUATION[s.valuationStatus] : null;
  });
  protected readonly marketAllocation = computed<AllocationBreakdown | null>(() => this.allocation()?.marketValue ?? null);

  protected priceDraft = { price: null as number | null, asOf: marketToday(), source: '', note: '' };
  protected readonly maxDate = marketToday();

  constructor() {
    effect(() => this.load(this.id()));
  }

  protected reload(): void {
    this.revision.update((r) => r + 1);
    this.load(this.id());
  }

  protected onSaved(): void {
    this.editing.set(null);
    this.notice.set($localize`:@@portfolioDashboard.transactionSavedHoldingsRecalculated:Transaction saved; holdings recalculated.`);
    this.reload();
  }

  protected onInstrumentCreated(instrument: Instrument): void {
    this.instruments.update((list) => [...list, instrument].sort((a, b) => a.symbol.localeCompare(b.symbol)));
  }

  protected deleteTransaction(t: Transaction): void {
    if (!confirm($localize`:@@portfolioDashboard.confirmDeleteTransaction:Delete the ${this.typeLabel(t.type)}:type: of ${t.quantity}:quantity: ${t.symbol}:symbol: on ${t.tradeDate}:date:?`)) {
      return;
    }
    this.api.deleteTransaction(this.id(), t.id).subscribe({
      next: () => {
        this.notice.set($localize`:@@portfolioDashboard.transactionDeletedHoldingsRecalculated:Transaction deleted; holdings recalculated.`);
        this.reload();
      },
      error: (err) => this.error.set(errorMessage(err, $localize`:@@portfolioDashboard.error.deleteTransaction:Could not delete the transaction.`)),
    });
  }

  protected startPricing(holding: Holding): void {
    this.pricing.set(holding);
    this.priceDraft = { price: null, asOf: marketToday(), source: '', note: '' };
  }

  protected savePrice(): void {
    const holding = this.pricing();
    const d = this.priceDraft;
    if (!holding || !d.price || d.price <= 0 || !d.asOf || !d.source.trim()) {
      return;
    }
    this.instrumentApi
      .addPrice(holding.instrumentId, { price: d.price, asOf: d.asOf, source: d.source.trim(), note: d.note.trim() || null })
      .subscribe({
        next: () => {
          this.pricing.set(null);
          this.notice.set($localize`:@@portfolioDashboard.priceSaved:Price for ${holding.symbol}:symbol: saved.`);
          this.load(this.id());
        },
        error: (err) => this.error.set(errorMessage(err, $localize`:@@portfolioDashboard.error.savePrice:Could not save the price.`)),
      });
  }

  protected tone(value: number | null): string {
    return value === null ? '' : value > 0 ? 'pnl--gain' : value < 0 ? 'pnl--loss' : '';
  }

  protected typeLabel(type: string): string {
    return type === 'OPENING_POSITION' ? $localize`:@@portfolioDashboard.openingPosition:Opening position` : type === 'BUY' ? $localize`:@@portfolioDashboard.buy:Buy` : type === 'SELL' ? $localize`:@@portfolioDashboard.sell:Sell` : type;
  }

  private load(id: string): void {
    this.error.set(null);
    forkJoin({
      portfolio: this.api.get(id),
      summary: this.api.summary(id),
      holdings: this.api.holdings(id),
      allocation: this.api.allocation(id),
      transactions: this.api.transactions(id),
      instruments: this.instrumentApi.list(),
    }).subscribe({
      next: (data) => {
        this.portfolio.set(data.portfolio);
        this.summary.set(data.summary);
        this.holdings.set(data.holdings);
        this.allocation.set(data.allocation);
        this.transactions.set(data.transactions.content);
        this.instruments.set(data.instruments);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, $localize`:@@portfolioDashboard.error.loadPortfolio:Could not load the portfolio.`));
      },
    });
  }
}

import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { errorMessage } from '../../core/api/error-message';
import { InstrumentApiService, PortfolioApiService } from '../../core/api/portfolio-api.service';
import { Instrument, InstrumentType, Transaction, TransactionType } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';

interface Draft {
  instrumentId: string;
  type: TransactionType;
  tradeDate: string;
  quantity: number | null;
  price: number | null;
  fees: number | null;
  taxes: number | null;
  notes: string;
}

/** Today in Asia/Ho_Chi_Minh as yyyy-MM-dd, the date the backend validates against. */
export function marketToday(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }).format(now);
}

/**
 * Adds or edits one transaction. The backend replays the whole history and rejects a change that would
 * sell more than was held on that date; its message is shown here.
 */
@Component({
  selector: 'app-transaction-form',
  imports: [FormsModule, Alert],
  templateUrl: './transaction-form.html',
})
export class TransactionForm {
  private readonly portfolios = inject(PortfolioApiService);
  private readonly instrumentApi = inject(InstrumentApiService);

  readonly portfolioId = input.required<string>();
  readonly currency = input('VND');
  readonly instruments = input<Instrument[]>([]);
  readonly transaction = input<Transaction | null>(null);
  readonly saved = output<Transaction>();
  readonly instrumentCreated = output<Instrument>();
  readonly cancelled = output<void>();

  protected readonly types: { value: TransactionType; label: string }[] = [
    { value: 'BUY', label: 'Buy' },
    { value: 'SELL', label: 'Sell' },
    { value: 'OPENING_POSITION', label: 'Opening position' },
  ];
  protected readonly instrumentTypes: InstrumentType[] = ['STOCK', 'ETF', 'FUND', 'BOND', 'OTHER'];
  protected readonly maxDate = marketToday();
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly addingInstrument = signal(false);
  protected readonly editing = computed(() => this.transaction() !== null);

  protected draft: Draft = this.empty();
  protected newInstrument = { symbol: '', name: '', instrumentType: 'STOCK' as InstrumentType, aliases: '' };

  constructor() {
    effect(() => {
      const t = this.transaction();
      this.draft = t
        ? {
            instrumentId: t.instrumentId,
            type: t.type,
            tradeDate: t.tradeDate,
            quantity: t.quantity,
            price: t.price,
            fees: t.fees,
            taxes: t.taxes,
            notes: t.notes ?? '',
          }
        : this.empty();
      this.error.set(null);
    });
  }

  /** Client-side checks mirroring the backend; the backend remains authoritative. */
  protected problems(): string[] {
    const d = this.draft;
    const problems: string[] = [];
    if (!d.instrumentId) problems.push('Choose an instrument.');
    if (!d.tradeDate) problems.push('Enter the trade date.');
    else if (d.tradeDate > this.maxDate) problems.push('The trade date cannot be in the future.');
    if (d.quantity === null || !(d.quantity > 0)) problems.push('Quantity must be greater than 0.');
    if (d.price === null || d.price < 0) problems.push('Price must be 0 or more.');
    if ((d.fees ?? 0) < 0 || (d.taxes ?? 0) < 0) problems.push('Fees and taxes cannot be negative.');
    return problems;
  }

  protected priceLabel(): string {
    return this.draft.type === 'OPENING_POSITION' ? `Cost per unit (${this.currency()})` : `Price per unit (${this.currency()})`;
  }

  protected submit(): void {
    if (this.problems().length || this.saving()) {
      return;
    }
    const d = this.draft;
    const request = {
      instrumentId: d.instrumentId,
      type: d.type,
      tradeDate: d.tradeDate,
      quantity: d.quantity!,
      price: d.price!,
      fees: d.fees ?? 0,
      taxes: d.taxes ?? 0,
      notes: d.notes.trim() || null,
    };
    const existing = this.transaction();
    const call = existing
      ? this.portfolios.updateTransaction(this.portfolioId(), existing.id, request)
      : this.portfolios.addTransaction(this.portfolioId(), request);
    this.saving.set(true);
    this.error.set(null);
    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (!existing) {
          this.draft = { ...this.empty(), instrumentId: d.instrumentId, type: d.type };
        }
        this.saved.emit(saved);
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'Could not save the transaction.'));
      },
    });
  }

  protected createInstrument(): void {
    const n = this.newInstrument;
    if (!n.symbol.trim() || !n.name.trim()) {
      return;
    }
    this.instrumentApi
      .create({
        symbol: n.symbol.trim(),
        name: n.name.trim(),
        instrumentType: n.instrumentType,
        currency: this.currency(),
        aliases: n.aliases.split(',').map((a) => a.trim()).filter(Boolean),
      })
      .subscribe({
        next: (instrument) => {
          this.instrumentCreated.emit(instrument);
          this.draft.instrumentId = instrument.id;
          this.addingInstrument.set(false);
          this.newInstrument = { symbol: '', name: '', instrumentType: 'STOCK', aliases: '' };
        },
        error: (err) => this.error.set(errorMessage(err, 'Could not create the instrument.')),
      });
  }

  private empty(): Draft {
    return { instrumentId: '', type: 'BUY', tradeDate: marketToday(), quantity: null, price: null, fees: 0, taxes: 0, notes: '' };
  }
}

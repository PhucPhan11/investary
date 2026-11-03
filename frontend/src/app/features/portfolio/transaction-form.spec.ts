import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { Instrument } from '../../core/models/portfolio.model';
import { TransactionForm, marketToday } from './transaction-form';

const fpt: Instrument = { id: 'i1', symbol: 'FPT', name: 'FPT', instrumentType: 'STOCK', exchange: 'HOSE', currency: 'VND', aliases: [] };

describe('TransactionForm', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TransactionForm],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(TransactionForm);
    fixture.componentRef.setInput('portfolioId', 'p1');
    fixture.componentRef.setInput('instruments', [fpt]);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  async function fill(fixture: Awaited<ReturnType<typeof render>>, values: Record<string, string>) {
    const el = fixture.nativeElement as HTMLElement;
    for (const [name, value] of Object.entries(values)) {
      const input = el.querySelector<HTMLInputElement | HTMLSelectElement>(`[name="${name}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('reports invalid input and keeps submit disabled', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;

    await fill(fixture, { instrumentId: 'i1', quantity: '0', price: '-5', tradeDate: '2999-01-01' });

    const problems = el.querySelector('.form-problems')!.textContent!;
    expect(problems).toContain('Quantity must be greater than 0');
    expect(problems).toContain('Price must be 0 or more');
    expect(problems).toContain('cannot be in the future');
    expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
  });

  it('submits an opening position and shows the backend error for an oversell', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    const saved: unknown[] = [];
    fixture.componentInstance.saved.subscribe((t) => saved.push(t));

    await fill(fixture, { instrumentId: 'i1', type: 'OPENING_POSITION', quantity: '100', price: '95000' });
    expect(el.textContent).toContain('Cost per unit (VND)');
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    const request = http.expectOne('/api/v1/portfolios/p1/transactions');
    expect(request.request.body).toEqual({
      instrumentId: 'i1',
      type: 'OPENING_POSITION',
      tradeDate: marketToday(),
      quantity: 100,
      price: 95000,
      fees: 0,
      taxes: 0,
      notes: null,
    });
    request.flush({ id: 't1' });
    expect(saved.length).toBe(1);

    await fill(fixture, { instrumentId: 'i1', type: 'SELL', quantity: '500', price: '100000' });
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    http.expectOne('/api/v1/portfolios/p1/transactions').flush(
      { code: 'INSUFFICIENT_HOLDINGS', detail: 'Sell of 500 FPT on 2026-10-30 exceeds the 100 held at that date' },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')!.textContent).toContain('exceeds the 100 held');
  });

  it('formats the market date in Asia/Ho_Chi_Minh', () => {
    expect(marketToday(new Date('2026-10-30T18:00:00Z'))).toBe('2026-10-31');
  });
});

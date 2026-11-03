import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { DecisionRevision } from '../../core/models/journal.model';
import { decision, SNAPSHOT } from '../../testing/journal-fixtures';
import { changeRows, DecisionDetail } from './decision-detail';

const HISTORY: DecisionRevision[] = [
  { sequence: 1, kind: 'CREATED', reason: null, statusAfter: 'DRAFT', changes: { title: { from: null, to: 'Mua thêm FPT' } }, createdAt: '2026-10-30T02:00:00Z' },
  { sequence: 2, kind: 'FINALIZED', reason: null, statusAfter: 'DECIDED', changes: { status: { from: 'DRAFT', to: 'DECIDED' } }, createdAt: '2026-10-30T03:00:00Z' },
];

describe('DecisionDetail', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(detail = decision(), history: DecisionRevision[] = []) {
    const fixture = TestBed.createComponent(DecisionDetail);
    fixture.componentRef.setInput('id', 'd1');
    fixture.detectChanges();
    http.expectOne('/api/v1/decisions/d1').flush(detail);
    http.expectOne('/api/v1/decisions/d1/history').flush(history);
    fixture.detectChanges();
    return fixture;
  }

  it('finalizes a draft with the version it shows and reloads the history', () => {
    const fixture = create();
    expect(fixture.nativeElement.textContent).toContain('The snapshot is stored when the decision is finalized');

    const finalize = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.includes('Finalize'));
    finalize.click();
    const request = http.expectOne('/api/v1/decisions/d1/finalize');
    expect(request.request.body).toEqual({ expectedVersion: 0, reason: null });
    request.flush(decision({ status: 'DECIDED', version: 1, snapshot: SNAPSHOT, actions: ['AMEND', 'EXECUTE', 'CANCEL'] }));
    http.expectOne('/api/v1/decisions/d1/history').flush(HISTORY);
    fixture.detectChanges();

    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Decided');
    expect(text).toContain('Held 100 units');
    expect(text).toContain('Missing prices: VNM');
    expect(text).toContain('No price');
    expect(text).toContain('Risk tolerance MODERATE');
    expect(text).toContain('Finalized');
    expect(text).toContain('DRAFT → DECIDED');
  });

  it('requires a reason before cancelling', () => {
    const fixture = create(decision({ status: 'DECIDED', version: 2, snapshot: SNAPSHOT, actions: ['AMEND', 'EXECUTE', 'CANCEL'] }));
    const cancel = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.includes('Cancel decision'));
    cancel.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Give a reason to cancel');

    const reason: HTMLInputElement = fixture.nativeElement.querySelector('input[name="reason"]');
    reason.value = 'Không còn phù hợp';
    reason.dispatchEvent(new Event('input'));
    cancel.click();
    const request = http.expectOne('/api/v1/decisions/d1/cancel');
    expect(request.request.body).toEqual({ expectedVersion: 2, reason: 'Không còn phù hợp' });
    request.flush(decision({ status: 'CANCELLED', version: 3, actions: [], cancelReason: 'Không còn phù hợp' }));
    http.expectOne('/api/v1/decisions/d1/history').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Cancelled');
  });

  it('shows unavailable evidence with its copied details and linked transaction states', () => {
    const fixture = create(
      decision({
        status: 'EXECUTED',
        actions: ['AMEND', 'ATTACH_EVIDENCE', 'LINK_TRANSACTION'],
        evidence: [
          {
            id: 'e1', kind: 'DOCUMENT', role: 'SUPPORTING', sourceId: 'doc1', available: false, title: 'fpt-tong-hop.pdf',
            publisher: 'SSI', publishedOn: '2026-10-27', location: null, excerpt: null, note: null, attachedAt: '2026-10-30T02:00:00Z',
            removedAt: null, removedReason: null,
          },
          {
            id: 'e2', kind: 'CITATION', role: 'CONTRADICTING', sourceId: 'c1', available: true, title: 'Báo cáo ngành',
            publisher: 'VND', publishedOn: null, location: 'trang 3', excerpt: 'Đoạn trích nguyên văn', note: null,
            attachedAt: '2026-10-30T02:00:00Z', removedAt: null, removedReason: null,
          },
        ],
        transactions: [
          {
            id: 'l1', transactionId: 't1', portfolioId: 'p1', symbol: 'FPT', transactionType: 'BUY', tradeDate: '2026-10-29', quantity: 20,
            price: 105000, note: null, linkedAt: '2026-10-30T02:00:00Z', removedAt: null, removedReason: null, state: 'DELETED', current: null,
          },
        ],
      }),
    );
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('fpt-tong-hop.pdf');
    expect(text).toContain('Source unavailable');
    expect(fixture.nativeElement.querySelector('a[href="/documents/doc1"]')).toBeNull();
    expect(text).toContain('Đoạn trích nguyên văn');
    expect(text).toContain('Contradicting');
    expect(text).toContain('Transaction deleted');
  });

  it('attaches evidence chosen from the document library', async () => {
    const fixture = create();
    const attach = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.includes('Attach evidence'));
    attach.click();
    http.expectOne('/api/v1/documents?page=0&size=50&sort=uploadedAt,desc').flush({
      content: [{ id: 'doc1', originalFilename: 'fpt-tong-hop.pdf' }], page: 0, size: 50, totalElements: 1, totalPages: 1,
    });
    fixture.detectChanges();
    await fixture.whenStable();

    const source: HTMLSelectElement = fixture.nativeElement.querySelector('select[name="sourceId"]');
    source.value = source.options[1].value;
    source.dispatchEvent(new Event('change'));
    fixture.nativeElement.querySelector('[data-testid="evidence-form"]').dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/v1/decisions/d1/evidence');
    expect(request.request.body).toEqual({ kind: 'DOCUMENT', role: 'SUPPORTING', sourceId: 'doc1', note: null, expectedVersion: 0 });
    request.flush(decision({ version: 1 }));
    http.expectOne('/api/v1/decisions/d1/history').flush([]);
  });

  it('formats revision changes', () => {
    expect(changeRows({ title: { from: 'A', to: 'B' }, kind: 'DOCUMENT', quantity: 5 })).toEqual([
      { field: 'title', from: 'A', to: 'B' },
      { field: 'kind', value: 'DOCUMENT' },
      { field: 'quantity', value: '5' },
    ]);
  });
});

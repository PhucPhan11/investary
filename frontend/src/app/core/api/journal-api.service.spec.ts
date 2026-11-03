import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { JournalApiService } from './journal-api.service';

describe('JournalApiService', () => {
  let api: JournalApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(JournalApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends only the filters that are set', () => {
    api.list({ ticker: ' fpt ', portfolioId: '', status: 'DECIDED', type: '' }, 1, 10).subscribe();
    http.expectOne('/api/v1/decisions?page=1&size=10&ticker=fpt&status=DECIDED').flush({ content: [] });
  });

  it('carries the expected version on every write', () => {
    api.finalize('d1', 2).subscribe();
    const finalize = http.expectOne('/api/v1/decisions/d1/finalize');
    expect(finalize.request.body).toEqual({ expectedVersion: 2, reason: null });
    finalize.flush({});

    api.cancel('d1', 3, 'Đổi ý').subscribe();
    expect(http.expectOne('/api/v1/decisions/d1/cancel').request.body).toEqual({ expectedVersion: 3, reason: 'Đổi ý' });

    api.removeEvidence('d1', 'e1', 4, 'Trùng').subscribe();
    const remove = http.expectOne((r) => r.url === '/api/v1/decisions/d1/evidence/e1');
    expect(remove.request.method).toBe('DELETE');
    expect(remove.request.params.get('expectedVersion')).toBe('4');
    expect(remove.request.params.get('reason')).toBe('Trùng');

    api.linkTransaction('d1', 't1', 5).subscribe();
    expect(http.expectOne('/api/v1/decisions/d1/transactions').request.body).toEqual({ transactionId: 't1', expectedVersion: 5, note: null });
  });
});

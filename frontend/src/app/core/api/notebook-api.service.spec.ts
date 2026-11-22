import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { NotebookApiService } from './notebook-api.service';

describe('NotebookApiService', () => {
  let api: NotebookApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(NotebookApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends only the given sources and drops empty filter fields', () => {
    api.addSources('nb1', { documentIds: ['d1'], messageIds: [] }).subscribe();
    let request = http.expectOne('/api/v1/notebooks/nb1/sources');
    expect(request.request.body).toEqual({ documentIds: ['d1'] });
    request.flush({});

    api.addSources('nb1', { filter: { ticker: 'SCS', brokerage: '', sourceType: null, from: '2026-10-01', to: '' } }).subscribe();
    request = http.expectOne('/api/v1/notebooks/nb1/sources');
    expect(request.request.body).toEqual({ filter: { ticker: 'SCS', from: '2026-10-01' } });
    request.flush({});
  });

  it('sends a ticker only for per-ticker notes and the version on update', () => {
    api.requestNote('nb1', 'SUMMARY', null).subscribe();
    expect(http.expectOne('/api/v1/notebooks/nb1/notes').request.body).toEqual({ kind: 'SUMMARY' });

    api.requestNote('nb1', 'TIMELINE', 'SCS').subscribe();
    expect(http.expectOne('/api/v1/notebooks/nb1/notes').request.body).toEqual({ kind: 'TIMELINE', ticker: 'SCS' });

    api.update('nb1', 'Mới', null, 3).subscribe();
    const update = http.expectOne('/api/v1/notebooks/nb1');
    expect(update.request.method).toBe('PATCH');
    expect(update.request.body).toEqual({ title: 'Mới', description: null, expectedVersion: 3 });
  });
});

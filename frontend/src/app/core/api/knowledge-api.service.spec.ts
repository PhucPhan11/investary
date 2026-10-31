import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { KnowledgeApiService, ResearchApiService } from './knowledge-api.service';

describe('Knowledge and research API services', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('queues indexing for documents and emails', () => {
    const api = TestBed.inject(KnowledgeApiService);
    api.index('DOCUMENT', 'd1').subscribe();
    api.sourceStatus('EMAIL', 'e1').subscribe();

    expect(http.expectOne('/api/v1/knowledge/index/documents/d1').request.method).toBe('POST');
    expect(http.expectOne('/api/v1/knowledge/emails/e1').request.method).toBe('GET');
  });

  it('sends only non-empty filters with a question', () => {
    TestBed.inject(ResearchApiService)
      .ask('Rủi ro của SCS?', { ticker: ' SCS ', brokerage: '', sourceType: null, from: '2026-10-01' })
      .subscribe();

    const request = http.expectOne('/api/v1/research/ask');
    expect(request.request.body).toEqual({ question: 'Rủi ro của SCS?', ticker: 'SCS', from: '2026-10-01' });
  });
});

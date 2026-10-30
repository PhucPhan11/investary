import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { IntelligenceApiService } from './intelligence-api.service';
import { InstrumentApiService, PortfolioApiService } from './portfolio-api.service';

describe('Portfolio and intelligence API services', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('always sends the portfolio explicitly when asking', () => {
    TestBed.inject(IntelligenceApiService).ask('SCS?', 'p1').subscribe();
    const request = http.expectOne('/api/v1/intelligence/ask');
    expect(request.request.body).toEqual({ question: 'SCS?', portfolioId: 'p1' });
    request.flush({});
  });

  it('drops empty research filters', () => {
    TestBed.inject(IntelligenceApiService).createAssessment('p1', { brokerage: '  ', from: '2026-10-01', to: null }).subscribe();
    const request = http.expectOne('/api/v1/intelligence/portfolios/p1/impact-assessments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ from: '2026-10-01' });
    request.flush({});
  });

  it('uses the portfolio and instrument endpoints', () => {
    const portfolios = TestBed.inject(PortfolioApiService);
    portfolios.transactions('p1', 2, 20).subscribe();
    http.expectOne('/api/v1/portfolios/p1/transactions?page=2&size=20').flush({ content: [] });
    portfolios.deleteTransaction('p1', 't 1').subscribe();
    http.expectOne('/api/v1/portfolios/p1/transactions/t%201').flush(null);

    TestBed.inject(InstrumentApiService).list(' fpt ').subscribe();
    http.expectOne('/api/v1/instruments?q=fpt').flush([]);
  });
});

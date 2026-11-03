import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { BriefApiService } from './brief-api.service';

describe('BriefApiService', () => {
  let http: HttpTestingController;
  let api: BriefApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(BriefApiService);
  });

  afterEach(() => http.verify());

  it('lists briefs with only the filters that are set', () => {
    api.list({ portfolioId: 'p1', from: '2026-10-01', to: '', status: '' }, 2, 10).subscribe();
    http.expectOne('/api/v1/briefs?page=2&size=10&portfolioId=p1&from=2026-10-01').flush({ content: [] });
  });

  it('requests generation, regeneration and job status', () => {
    api.generate('p 1', '2026-11-01').subscribe();
    const generate = http.expectOne('/api/v1/briefs/generate');
    expect(generate.request.method).toBe('POST');
    expect(generate.request.body).toEqual({ portfolioId: 'p 1', date: '2026-11-01' });
    generate.flush({});

    api.generate().subscribe();
    expect(http.expectOne('/api/v1/briefs/generate').request.body).toEqual({ portfolioId: null, date: null });

    api.regenerate('b 1').subscribe();
    expect(http.expectOne('/api/v1/briefs/b%201/regenerate').request.method).toBe('POST');
    api.job('j1').subscribe();
    http.expectOne('/api/v1/briefs/jobs/j1').flush({});
    api.get('b1').subscribe();
    http.expectOne('/api/v1/briefs/b1').flush({});
  });
});

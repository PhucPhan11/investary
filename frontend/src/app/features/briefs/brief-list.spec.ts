import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { BriefListItem } from '../../core/models/brief.model';
import { BriefList } from './brief-list';

const ITEM: BriefListItem = {
  id: 'b1',
  portfolioId: 'p1',
  portfolioName: 'Danh mục chính',
  triggerType: 'SCHEDULED',
  status: 'PARTIAL',
  researchState: 'PENDING_ONLY',
  periodStart: '2026-10-31T01:00:00Z',
  periodEnd: '2026-11-01T01:00:00Z',
  zone: 'Asia/Ho_Chi_Minh',
  businessDate: '2026-11-01',
  partialPeriod: false,
  generatedAt: '2026-11-01T01:00:05Z',
  revision: 2,
  aiStatus: 'NOT_CONFIGURED',
  evidenceStatus: null,
  newSources: 0,
  pendingSources: 3,
  errorCode: null,
};

describe('BriefList', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function create(jobs: unknown[] = []) {
    const fixture = TestBed.createComponent(BriefList);
    fixture.detectChanges();
    http.expectOne('/api/v1/portfolios').flush([{ id: 'p1', name: 'Danh mục chính' }]);
    http.expectOne('/api/v1/briefs/jobs?page=0&size=5').flush({ content: jobs, page: 0, size: 5, totalElements: jobs.length, totalPages: 1 });
    return fixture;
  }

  it('lists briefs with their state, counts and revision', () => {
    const fixture = create();
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ content: [ITEM], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;

    expect(text).toContain('Danh mục chính');
    expect(text).toContain('Research still processing');
    expect(text).toContain('0 / 3');
    expect(text).toContain('rev. 2');
    expect(text).toContain('Partial (no AI part)');
  });

  it('shows the empty and error states', () => {
    const fixture = create();
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No briefs yet');

    (fixture.componentInstance as unknown as { load: (page: number) => void }).load(0);
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ detail: 'down' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.alert--error')).not.toBeNull();
  });

  it('generates a brief for the only portfolio and polls the job until it is done', () => {
    vi.useFakeTimers();
    const fixture = create();
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('form button[type=submit]') as HTMLButtonElement).click();
    const generate = http.expectOne('/api/v1/briefs/generate');
    expect(generate.request.body).toEqual({ portfolioId: 'p1', date: null });
    generate.flush({ id: 'j1', status: 'PENDING', attempts: 0, maxAttempts: 3, periodStart: ITEM.periodStart, periodEnd: ITEM.periodEnd });
    fixture.detectChanges();

    vi.advanceTimersByTime(2000);
    http.expectOne('/api/v1/briefs/jobs/j1').flush({ id: 'j1', status: 'SUCCEEDED', outcome: 'CREATED', briefId: 'b1' });
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ content: [ITEM], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    http.expectOne('/api/v1/briefs/jobs?page=0&size=5').flush({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();

    const job = fixture.nativeElement.querySelector('[data-testid=brief-job]') as HTMLElement;
    expect(job.textContent).toContain('Brief ready');
    expect(job.querySelector('a')?.getAttribute('href')).toBe('/briefs/b1');
  });

  it('shows the generation status and retries a failed job', () => {
    vi.useFakeTimers();
    const failed = { id: 'j9', triggerType: 'SCHEDULED', status: 'FAILED', businessDate: '2026-11-01', createdAt: ITEM.generatedAt,
      errorSummary: 'The AI model did not answer in time', attempts: 3, maxAttempts: 3 };
    const fixture = create([failed]);
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    const status = fixture.nativeElement.querySelector('[data-testid=generation-status]') as HTMLElement;
    expect(status.textContent).toContain('The AI model did not answer in time');

    (status.querySelector('button') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/v1/briefs/jobs/j9/retry' }).flush({ ...failed, status: 'PENDING', errorSummary: null });
    http.expectOne('/api/v1/briefs/jobs?page=0&size=5').flush({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    vi.advanceTimersByTime(2000);
    http.expectOne('/api/v1/briefs/jobs/j9').flush({ ...failed, status: 'SUCCEEDED', outcome: 'CREATED', briefId: 'b1', errorSummary: null });
    http.expectOne('/api/v1/briefs?page=0&size=20').flush({ content: [ITEM], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    http.expectOne('/api/v1/briefs/jobs?page=0&size=5').flush({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  });
});

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AutomationJob } from '../../core/models/automation.model';
import { job } from '../../testing/automation-fixtures';
import { JobHistory } from './job-history';


const EMPTY = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

describe('JobHistory', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [JobHistory],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(jobs: AutomationJob[], status?: string) {
    const fixture = TestBed.createComponent(JobHistory);
    if (status) {
      fixture.componentRef.setInput('status', status);
    }
    fixture.detectChanges();
    http.expectOne('/api/v1/automation/sources').flush([{ id: 'r1', name: 'TCBS' }]);
    http.expectOne(status ? `/api/v1/automation/jobs?page=0&size=20&status=${status}` : '/api/v1/automation/jobs?page=0&size=20')
      .flush({ ...EMPTY, content: jobs, totalElements: jobs.length, totalPages: 1 });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('lists jobs with their source, status, attempts and safe error', async () => {
    const fixture = await render([job(), job({ id: 'j2-0000', status: 'FAILED', retryable: false, retryBlockedReason: 'RETRY_LIMIT_REACHED' })]);
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(rows[0].textContent).toContain('Indexing');
    expect(rows[0].textContent).toContain('IMP cập nhật');
    expect(rows[0].textContent).toContain('3/3');
    expect(rows[0].textContent).toContain('INDEXING_FAILED: Embedding provider offline');
    expect(rows[0].querySelector('a[href="/emails/e1"]')).not.toBeNull();
    expect(rows[1].textContent).toContain('No retry');
  });

  it('sends the filters as query parameters', async () => {
    const fixture = await render([], 'FAILED');
    const c = fixture.componentInstance as unknown as { filters: Record<string, string>; applyFilters: () => void };
    expect(c.filters['status']).toBe('FAILED');
    c.filters['type'] = 'ASSESS';
    c.filters['sourceType'] = 'EMAIL';
    c.filters['ruleId'] = 'r1';
    c.filters['from'] = '2026-10-01';
    c.filters['to'] = '2026-10-31';
    c.applyFilters();
    const request = http.expectOne((r) => r.url === '/api/v1/automation/jobs');
    expect(request.request.params.get('status')).toBe('FAILED');
    expect(request.request.params.get('type')).toBe('ASSESS');
    expect(request.request.params.get('sourceType')).toBe('EMAIL');
    expect(request.request.params.get('ruleId')).toBe('r1');
    expect(request.request.params.get('from')).toBe('2026-10-01');
    expect(request.request.params.get('to')).toBe('2026-10-31');
    request.flush(EMPTY);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No jobs');

    c.filters['from'] = '2026-11-02';
    c.filters['to'] = '2026-11-01';
    c.applyFilters();
    fixture.detectChanges();
    http.expectNone((r) => r.url === '/api/v1/automation/jobs');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('must not be after the end date');
  });

  it('retries a failed job and reloads, and shows a refused retry', async () => {
    const fixture = await render([job()]);
    const retry = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Retry');
    (retry as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/v1/automation/jobs/j1-0000-0000/retry' })
      .flush({ code: 'JOB_NOT_RETRYABLE', detail: 'Only failed jobs can be retried; this job is PENDING' }, { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/v1/automation/jobs?page=0&size=20').flush({ ...EMPTY, content: [job({ status: 'PENDING', retryable: false })] });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Only failed jobs can be retried');
  });

  it('switches to brief jobs and retries a failed one', async () => {
    const fixture = await render([]);
    const tab = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tabs__tab')).find((b) => b.textContent?.includes('Brief'));
    (tab as HTMLButtonElement).click();
    http.expectOne('/api/v1/briefs/jobs?page=0&size=20').flush({ ...EMPTY, content: [{ id: 'bj1', triggerType: 'SCHEDULED', status: 'FAILED',
      businessDate: '2026-11-01', createdAt: '2026-11-01T01:00:00Z', attempts: 3, maxAttempts: 3, errorCode: 'AI_TIMEOUT',
      errorSummary: 'The AI model did not answer in time', briefId: null }] });
    fixture.detectChanges();
    const retry = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Retry');
    (retry as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/v1/briefs/jobs/bj1/retry' }).flush({ id: 'bj1', status: 'PENDING', businessDate: '2026-11-01' });
    http.expectOne('/api/v1/briefs/jobs?page=0&size=20').flush(EMPTY);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('was queued again');
  });
});

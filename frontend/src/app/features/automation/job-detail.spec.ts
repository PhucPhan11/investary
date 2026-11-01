import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { JobDetail } from '../../core/models/automation.model';
import { JobDetailPage } from './job-detail';
import { job } from '../../testing/automation-fixtures';

function detail(overrides: Partial<JobDetail['job']> = {}): JobDetail {
  return {
    job: job(overrides),
    workflow: {
      id: 'w1',
      sourceType: 'EMAIL',
      sourceId: 'e1',
      status: 'FAILED',
      trigger: 'GMAIL',
      processingVersion: 'w1',
      startedAt: null,
      completedAt: null,
      updatedAt: null,
      importedBeforeAutomation: false,
      stages: [
        { stage: 'EMAIL_IMPORTED', status: 'COMPLETED', reason: null, counts: {}, startedAt: null, completedAt: null },
        { stage: 'KNOWLEDGE_INDEXED', status: 'FAILED', reason: 'INDEXING_FAILED', counts: {}, startedAt: null, completedAt: null },
        { stage: 'PORTFOLIO_MATCHED', status: 'SKIPPED', reason: 'NOT_REACHED', counts: {}, startedAt: null, completedAt: null },
        { stage: 'COMPLETED', status: 'FAILED', reason: null, counts: {}, startedAt: null, completedAt: null },
      ],
      matches: [],
      jobs: [],
    },
    attempts: [
      { attempt: 1, trigger: 'AUTO', startedAt: '2026-11-01T01:00:00Z', finishedAt: '2026-11-01T01:01:00Z', result: 'RETRY_SCHEDULED',
        errorCode: 'AI_TIMEOUT', errorSummary: 'The AI model did not answer in time' },
      { attempt: 2, trigger: 'AUTO', startedAt: '2026-11-01T01:02:00Z', finishedAt: '2026-11-01T01:03:00Z', result: 'FAILED',
        errorCode: 'INDEXING_FAILED', errorSummary: 'Embedding provider offline' },
    ],
  };
}

describe('JobDetailPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [JobDetailPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function render(value: JobDetail) {
    const fixture = TestBed.createComponent(JobDetailPage);
    fixture.componentRef.setInput('id', value.job.id);
    fixture.detectChanges();
    http.expectOne(`/api/v1/automation/jobs/${value.job.id}`).flush(value);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the stages, attempt history and related source', () => {
    const fixture = render(detail());
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.workflow-stages li')).toHaveLength(3);
    expect(el.textContent).toContain('Not reached because an earlier stage failed.');
    expect(el.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(el.textContent).toContain('AI_TIMEOUT: The AI model did not answer in time');
    expect(el.querySelector('a[href="/emails/e1"]')?.textContent).toContain('IMP cập nhật');
  });

  it('retries the job and polls until it finishes', () => {
    const fixture = render(detail());
    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.includes('Retry'));
    (button as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/v1/automation/jobs/j1-0000-0000/retry' }).flush(job({ status: 'PENDING' }));
    http.expectOne('/api/v1/automation/jobs/j1-0000-0000').flush(detail({ status: 'PENDING', retryable: false }));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('this page refreshes until it finishes');

    vi.advanceTimersByTime(3000);
    http.expectOne('/api/v1/automation/jobs/j1-0000-0000').flush(detail({ status: 'SUCCEEDED', retryable: false, errorCode: null }));
    fixture.detectChanges();
    vi.advanceTimersByTime(10_000);
    http.expectNone('/api/v1/automation/jobs/j1-0000-0000');
  });

  it('explains why a failed job cannot be retried', () => {
    const fixture = render(detail({ retryable: false, retryBlockedReason: 'SOURCE_DELETED', sourceTitle: null }));
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('The source was deleted; a retry cannot succeed.');
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Retry'))).toBe(false);
  });
});

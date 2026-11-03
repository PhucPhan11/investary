import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AutomationJob, ResearchWorkflow, WorkflowStageView } from '../../core/models/automation.model';
import { WorkflowStatusPanel } from './workflow-status';

function stage(name: WorkflowStageView['stage'], status: WorkflowStageView['status'], reason: string | null = null): WorkflowStageView {
  return { stage: name, status, reason, counts: {}, startedAt: null, completedAt: null };
}

function failedJob(): AutomationJob {
  return {
    id: 'j1',
    type: 'INDEX',
    workflowId: 'w1',
    sourceType: 'EMAIL',
    sourceId: 'e1',
    portfolioId: null,
    status: 'FAILED',
    outcome: null,
    attempts: 3,
    maxAttempts: 3,
    nextAttemptAt: null,
    startedAt: null,
    completedAt: null,
    errorCode: 'INDEXING_FAILED',
    errorSummary: 'Embedding provider offline',
    processingVersion: 'w1',
    inputTokens: null,
    outputTokens: null,
    createdAt: '2026-10-31T00:00:00Z',
    updatedAt: '2026-10-31T00:00:00Z',
  };
}

function workflow(changes: Partial<ResearchWorkflow> = {}): ResearchWorkflow {
  return {
    id: 'w1',
    sourceType: 'EMAIL',
    sourceId: 'e1',
    status: 'COMPLETED',
    trigger: 'GMAIL',
    processingVersion: 'w1',
    startedAt: null,
    completedAt: null,
    updatedAt: null,
    importedBeforeAutomation: false,
    stages: [
      stage('EMAIL_IMPORTED', 'COMPLETED'),
      stage('DOCUMENTS_PROCESSED', 'SKIPPED', 'AUTO_DOWNLOAD_DISABLED'),
      stage('PORTFOLIO_MATCHED', 'COMPLETED'),
      stage('COMPLETED', 'COMPLETED'),
    ],
    matches: [
      { portfolioId: 'p1', instrumentId: 'i1', symbol: 'IMP', matchKind: 'DIRECT', publishedOn: null, matchedAt: '' },
      { portfolioId: 'p2', instrumentId: 'i2', symbol: 'IMP', matchKind: 'DIRECT', publishedOn: null, matchedAt: '' },
    ],
    jobs: [],
    ...changes,
  };
}

describe('WorkflowStatusPanel', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [WorkflowStatusPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function render() {
    const fixture = TestBed.createComponent(WorkflowStatusPanel);
    fixture.componentRef.setInput('sourceId', 'e1');
    fixture.detectChanges();
    return fixture;
  }

  it('shows the stages, the skip reason and the matched holdings once', () => {
    const fixture = render();
    http.expectOne('/api/v1/automation/workflows/e1').flush(workflow());
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.workflow-stages li').length).toBe(3);
    expect(el.textContent).toContain('Automatic download is off for this source');
    expect(el.textContent).toContain('Relevant holdings: IMP');
    expect(el.querySelector('button')?.textContent?.trim()).toBe('Process again');
    vi.advanceTimersByTime(10_000);
    http.expectNone('/api/v1/automation/workflows/e1');
  });

  it('offers to process a source imported before automation and polls while it runs', () => {
    const fixture = render();
    http.expectOne('/api/v1/automation/workflows/e1').flush(workflow({ id: null, status: 'NOT_STARTED', importedBeforeAutomation: true,
      stages: [], matches: [] }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Imported before automated processing');

    el.querySelector<HTMLButtonElement>('button')!.click();
    http.expectOne({ method: 'POST', url: '/api/v1/automation/workflows/e1/process' })
      .flush(workflow({ status: 'RUNNING' }), { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();

    vi.advanceTimersByTime(3000);
    http.expectOne('/api/v1/automation/workflows/e1').flush(workflow());
    fixture.detectChanges();
    expect(el.textContent).toContain('Completed');
  });

  it('shows a failed step with its safe summary and retries it', () => {
    const fixture = render();
    http.expectOne('/api/v1/automation/workflows/e1').flush(workflow({ status: 'FAILED', jobs: [failedJob()] }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('INDEX failed after 3 attempts: Embedding provider offline');

    el.querySelector<HTMLButtonElement>('button')!.click();
    http.expectOne({ method: 'POST', url: '/api/v1/automation/workflows/e1/process' })
      .flush(workflow({ status: 'RUNNING' }), { status: 202, statusText: 'Accepted' });
    vi.advanceTimersByTime(3000);
    http.expectOne('/api/v1/automation/workflows/e1').flush(workflow());
  });
});

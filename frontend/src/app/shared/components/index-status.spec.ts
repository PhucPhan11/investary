import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { KnowledgeJob } from '../../core/models/knowledge.model';
import { IndexStatus } from './index-status';

function job(status: KnowledgeJob['status'], changes: Partial<KnowledgeJob> = {}): KnowledgeJob {
  return {
    id: status === 'NOT_INDEXED' ? null : 'k1',
    sourceType: 'DOCUMENT',
    sourceId: 'd1',
    status,
    statusReason: null,
    indexVersion: 'v1',
    currentVersion: true,
    chunkCount: 0,
    pageCount: null,
    attempts: 0,
    maxAttempts: 3,
    lastError: null,
    requestedAt: null,
    startedAt: null,
    completedAt: null,
    nextAttemptAt: null,
    ...changes,
  };
}

describe('IndexStatus', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [IndexStatus],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function render() {
    const fixture = TestBed.createComponent(IndexStatus);
    fixture.componentRef.setInput('sourceType', 'DOCUMENT');
    fixture.componentRef.setInput('sourceId', 'd1');
    fixture.detectChanges();
    return fixture;
  }

  it('retries a failed source and polls until it is indexed', () => {
    const fixture = render();
    http.expectOne('/api/v1/knowledge/documents/d1').flush(job('FAILED', { attempts: 3, lastError: 'Provider offline' }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Provider offline');

    el.querySelector<HTMLButtonElement>('button')!.click();
    expect(el.querySelector('button')!.textContent?.trim()).toBe('Retry');
    http.expectOne('/api/v1/knowledge/index/documents/d1').flush(job('PENDING'));
    fixture.detectChanges();
    expect(el.textContent).toContain('Queued');

    vi.advanceTimersByTime(3000);
    http.expectOne('/api/v1/knowledge/documents/d1').flush(job('INDEXED', { chunkCount: 5, pageCount: 2 }));
    fixture.detectChanges();
    expect(el.textContent).toMatch(/5 searchable passages\s+from 2 pages/);

    vi.advanceTimersByTime(10000);
    http.expectNone('/api/v1/knowledge/documents/d1');
  });

  it('explains scanned PDFs', () => {
    const fixture = render();
    http.expectOne('/api/v1/knowledge/documents/d1').flush(job('SKIPPED', { statusReason: 'OCR_REQUIRED' }));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('OCR is not supported yet');
  });
});

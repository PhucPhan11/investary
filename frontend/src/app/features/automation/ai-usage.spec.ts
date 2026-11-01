import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AiUsage } from '../../core/models/automation.model';
import { AiUsagePage } from './ai-usage';

function usage(): AiUsage {
  const bucket = (start: string, chat: number) => ({
    start, chatRequests: chat, chatFailures: 1, embeddingRequests: 10, embeddingFailures: 0, inputTokens: 1200, outputTokens: 300,
    briefGenerations: 1, aiBriefGenerations: 1, analyses: 2, automaticAnalyses: 2,
  });
  return {
    period: 'day',
    from: '2026-10-31',
    to: '2026-11-01',
    trackingSince: '2026-11-01',
    buckets: [bucket('2026-10-31', 0), bucket('2026-11-01', 4)],
    byPurpose: [{ operation: 'CHAT', purpose: 'ASSESSMENT', model: 'ollama:qwen3', requests: 4, failures: 1, inputTokens: 2400,
      outputTokens: 600 }],
    limits: { maxAutoAssessmentsPerDay: 20, automaticAssessmentsToday: 2, maxAiBriefsPerDay: 10, aiBriefsToday: 1, dailyTokenBudget: null,
      automaticTokensToday: 3000, maxConcurrentChat: 2, maxConcurrentEmbedding: 1 },
  };
}

describe('AiUsagePage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AiUsagePage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows totals, limits and the tracking note without any cost', () => {
    const fixture = TestBed.createComponent(AiUsagePage);
    fixture.detectChanges();
    http.expectOne('/api/v1/automation/usage?period=day&days=14').flush(usage());
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('LLM requests');
    expect(el.querySelector('[data-testid=limit-analyses]')?.textContent).toContain('2 / 20');
    expect(el.textContent).toContain('no budget');
    expect(el.querySelector('[data-testid=usage-note]')?.textContent).toContain('No monetary cost is shown');
    expect(el.textContent).toContain('Impact assessments');
    expect(el.textContent).not.toMatch(/\$|USD|VND/);
  });

  it('switches to weekly buckets and shows load errors', () => {
    const fixture = TestBed.createComponent(AiUsagePage);
    fixture.detectChanges();
    http.expectOne('/api/v1/automation/usage?period=day&days=14').flush(usage());
    fixture.detectChanges();
    const weekly = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tabs__tab')).find((b) => b.textContent?.includes('Weekly'));
    (weekly as HTMLButtonElement).click();
    http.expectOne('/api/v1/automation/usage?period=week&days=56').flush({ detail: 'down' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.alert--error')).not.toBeNull();
  });
});

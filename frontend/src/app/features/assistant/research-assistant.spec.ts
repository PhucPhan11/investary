import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AssistantAnswer, KnowledgeStatus } from '../../core/models/knowledge.model';
import { ResearchAssistant } from './research-assistant';

const status: KnowledgeStatus = {
  embedding: { available: true, provider: 'ollama', model: 'bge-m3', dimensions: 1024 },
  chat: { available: false, provider: null, model: 'none:qwen3:8b', dimensions: null },
  indexVersion: 'v1',
  autoIndex: true,
  workerEnabled: true,
  sources: { PENDING: 1, PROCESSING: 0, INDEXED: 3, FAILED: 0, SKIPPED: 1 },
  staleSources: 0,
  untrackedSources: 0,
  searchableSources: 3,
  searchableChunks: 12,
};

const answer: AssistantAnswer = {
  answer: 'Theo TCBS, SCS đang ở trạng thái Chờ mua [S1].',
  evidenceStatus: 'PARTIAL',
  citations: [
    {
      label: 'S1',
      sourceType: 'EMAIL',
      sourceId: 'e1',
      title: 'Cơ hội đầu tư | 28/10/2026',
      publisher: 'TCBS',
      publishedAt: '2026-10-28',
      dateBasis: 'EMAIL_SENT',
      pageStart: null,
      pageEnd: null,
      section: '3. Khuyến nghị cổ phiếu',
      location: '3. Khuyến nghị cổ phiếu',
      senderName: 'Research Desk',
      senderAddress: 'research@example.com',
      sentAt: '2026-10-28T12:14:40Z',
      chunkId: 'c1',
      excerpt: 'SCS - Chờ mua - Trong quý 3/2026...',
    },
  ],
  retrievedSourceIds: ['e1'],
  coverageGaps: ['SSI'],
  warnings: ['Không tìm thấy tài liệu đã lập chỉ mục từ: SSI.'],
  newestEvidenceDate: '2026-10-28',
  model: 'ollama:qwen3:8b',
  disclaimer: 'Không phải lời khuyên đầu tư.',
};

describe('ResearchAssistant', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ResearchAssistant],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render() {
    const fixture = TestBed.createComponent(ResearchAssistant);
    fixture.detectChanges();
    http.expectOne('/api/v1/knowledge/status').flush(status);
    fixture.detectChanges();
    return fixture;
  }

  it('reports a missing AI provider and knowledge base counts', () => {
    const el = render().nativeElement as HTMLElement;

    expect(el.textContent).toContain('The AI provider is not configured');
    expect(el.textContent).toContain('3 searchable sources');
    expect(el.textContent).toContain('1 waiting to be indexed');
  });

  it('asks a question and shows the answer with linked citations, gaps and warnings', () => {
    const fixture = render();
    const el = fixture.nativeElement as HTMLElement;

    el.querySelector<HTMLButtonElement>('.link-button')!.click();
    const request = http.expectOne('/api/v1/research/ask');
    expect(request.request.body).toEqual({ question: 'TCBS đang khuyến nghị gì về cổ phiếu SCS?' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Searching sources');

    request.flush(answer);
    fixture.detectChanges();

    expect(el.querySelector('.turn__text')!.textContent).toContain('Theo TCBS, SCS đang ở trạng thái Chờ mua');
    expect(el.querySelector('button.cite')!.textContent!.trim()).toBe('S1');
    expect(el.textContent).toContain('Partially supported');
    expect(el.textContent).toContain('No indexed evidence from: SSI.');
    expect(el.querySelector('.source__title')!.getAttribute('href')).toBe('/emails/e1');
    expect(el.querySelector('.source__meta')!.textContent).toContain('Email date');
    expect(el.querySelector('.source__excerpt')!.textContent).toContain('SCS - Chờ mua');

    el.querySelector<HTMLButtonElement>('button.cite')!.click();
    fixture.detectChanges();
    expect(el.querySelector('.source--active')).not.toBeNull();
  });

  it('shows provider errors on the question that failed', () => {
    const fixture = render();
    const el = fixture.nativeElement as HTMLElement;

    el.querySelector<HTMLButtonElement>('.link-button')!.click();
    http.expectOne('/api/v1/research/ask').flush(
      { code: 'AI_NOT_CONFIGURED', detail: 'No chat model is configured.' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    fixture.detectChanges();

    expect(el.querySelector('.turn [role="alert"]')!.textContent).toContain('No chat model is configured.');
  });
});

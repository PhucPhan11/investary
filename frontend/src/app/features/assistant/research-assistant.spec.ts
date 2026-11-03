import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PortfolioAnswer } from '../../core/models/intelligence.model';
import { AssistantAnswer, KnowledgeStatus } from '../../core/models/knowledge.model';
import { Portfolio } from '../../core/models/portfolio.model';
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

  function render(portfolios: Portfolio[] = []) {
    const fixture = TestBed.createComponent(ResearchAssistant);
    fixture.detectChanges();
    http.expectOne('/api/v1/knowledge/status').flush(status);
    http.expectOne('/api/v1/portfolios').flush(portfolios);
    fixture.detectChanges();
    return fixture;
  }

  function portfolio(id: string, name: string): Portfolio {
    return { id, name, description: null, baseCurrency: 'VND', transactionCount: 1, createdAt: '', updatedAt: '' };
  }

  function switchToPortfolioMode(el: HTMLElement): void {
    const radio = el.querySelector<HTMLInputElement>('input[value="PORTFOLIO"]')!;
    radio.checked = true;
    radio.dispatchEvent(new Event('change'));
  }

  it('never picks a portfolio when there are several', async () => {
    const fixture = render([portfolio('p1', 'Dài hạn'), portfolio('p2', 'Lướt sóng')]);
    const el = fixture.nativeElement as HTMLElement;

    switchToPortfolioMode(el);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="choose-portfolio"]')).not.toBeNull();
    expect(el.querySelectorAll('select[name="portfolioId"] option').length).toBe(3);
    const textarea = el.querySelector<HTMLTextAreaElement>('textarea[name="question"]')!;
    textarea.value = 'Danh mục của tôi có rủi ro gì?';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
    expect(el.querySelector('.assistant__filters')).toBeNull();
  });

  it('asks a portfolio-aware question and shows the portfolio facts apart from the cited answer', async () => {
    const fixture = render([portfolio('p1', 'Dài hạn')]);
    const el = fixture.nativeElement as HTMLElement;

    switchToPortfolioMode(el);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="choose-portfolio"]')).toBeNull();

    const textarea = el.querySelector<HTMLTextAreaElement>('textarea[name="question"]')!;
    textarea.value = 'SCS ảnh hưởng thế nào?';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

    const request = http.expectOne('/api/v1/intelligence/ask');
    expect(request.request.body).toEqual({ question: 'SCS ảnh hưởng thế nào?', portfolioId: 'p1' });
    const reply: PortfolioAnswer = {
      portfolioId: 'p1',
      portfolioName: 'Dài hạn',
      portfolioSummary: {} as PortfolioAnswer['portfolioSummary'],
      holdings: [],
      portfolioFacts: 'Danh mục: Dài hạn (tiền tệ VND)\n- SCS: số lượng 1000',
      profileConfigured: false,
      goalCount: 0,
      focusSymbols: ['SCS'],
      answer: answer.answer,
      evidenceStatus: 'SUPPORTED',
      citations: answer.citations,
      retrievedSourceIds: ['e1'],
      warnings: [],
      newestEvidenceDate: '2026-10-28',
      model: 'ollama:qwen3:8b',
      disclaimer: 'Không phải lời khuyên đầu tư.',
    };
    request.flush(reply);
    fixture.detectChanges();

    expect(el.textContent).toContain('Portfolio: Dài hạn');
    expect(el.querySelector('.portfolio-facts__text')!.textContent).toContain('SCS: số lượng 1000');
    expect(el.querySelector('.turn__text')!.textContent).toContain('Theo TCBS');
    expect(el.textContent).toContain('Supported by sources');
  });

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

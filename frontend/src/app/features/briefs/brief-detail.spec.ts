import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Brief } from '../../core/models/brief.model';
import { BriefDetail } from './brief-detail';

export function brief(overrides: Partial<Brief> = {}): Brief {
  return {
    id: 'b1',
    portfolioId: 'p1',
    portfolioName: 'Danh mục chính',
    briefType: 'DAILY',
    triggerType: 'SCHEDULED',
    status: 'COMPLETED',
    researchState: 'NEW_RESEARCH',
    periodStart: '2026-10-31T01:00:00Z',
    periodEnd: '2026-11-01T01:00:00Z',
    zone: 'Asia/Ho_Chi_Minh',
    businessDate: '2026-11-01',
    partialPeriod: false,
    generatedAt: '2026-11-01T01:00:05Z',
    sourceCutoff: '2026-11-01T01:00:05Z',
    language: 'vi',
    sections: [
      {
        key: 'PORTFOLIO_UPDATES',
        title: 'Cập nhật cho danh mục của tôi',
        note: null,
        evidenceStatus: 'SUPPORTED',
        items: [
          {
            kind: 'SOURCE_CLAIM',
            text: 'FPT: TCBS — «Báo cáo FPT». Trích nguồn: “<b>Lợi nhuận</b> tăng” [S1]',
            evidenceStatus: 'SUPPORTED',
            citations: ['S1'],
            symbol: 'FPT',
            relevance: 'DIRECT',
            publishedOn: '2026-10-30',
            dateBasis: 'PUBLISHED',
            brokerage: 'TCBS',
            assessmentId: null,
          },
          {
            kind: 'AI_INTERPRETATION',
            text: 'Lợi nhuận FPT tăng [S1, S2].',
            evidenceStatus: 'PARTIAL',
            citations: ['S1', 'S2'],
            symbol: 'FPT',
            relevance: null,
            publishedOn: null,
            dateBasis: null,
            brokerage: null,
            assessmentId: null,
          },
        ],
      },
    ],
    citations: [
      {
        label: 'S1',
        role: 'CITED',
        sourceType: 'DOCUMENT',
        sourceId: 'd1',
        title: 'Báo cáo FPT',
        brokerage: 'TCBS',
        publishedOn: '2026-10-30',
        dateBasis: 'PUBLISHED',
        sentAt: null,
        importedAt: '2026-10-31T04:00:00Z',
        indexedAt: '2026-10-31T05:00:00Z',
        pageStart: 1,
        pageEnd: 1,
        section: null,
        location: 'trang 1',
        excerpt: 'Lợi nhuận tăng',
        symbols: ['FPT'],
        relevance: 'DIRECT',
        oldPublication: false,
        lateArrival: false,
        receivedVia: [],
      },
      {
        label: 'S2',
        role: 'CITED',
        sourceType: 'EMAIL',
        sourceId: 'e1',
        title: 'Email SSI',
        brokerage: 'SSI',
        publishedOn: '2026-08-01',
        dateBasis: 'EMAIL_SENT',
        sentAt: '2026-08-01T02:00:00Z',
        importedAt: '2026-10-31T04:00:00Z',
        indexedAt: '2026-10-31T05:00:00Z',
        pageStart: null,
        pageEnd: null,
        section: 'Nội dung',
        location: 'Nội dung',
        excerpt: 'Email',
        symbols: ['FPT'],
        relevance: 'DIRECT',
        oldPublication: true,
        lateArrival: false,
        receivedVia: [],
      },
    ],
    pendingSources: [
      {
        role: 'PENDING',
        sourceType: 'DOCUMENT',
        sourceId: 'd9',
        title: 'Đang chờ.pdf',
        brokerage: null,
        publishedOn: null,
        dateBasis: null,
        sentAt: null,
        importedAt: '2026-10-31T06:00:00Z',
        indexedAt: null,
        state: 'PROCESSING',
      },
    ],
    omittedSources: [],
    limitations: [{ code: 'OLD_PUBLICATION', message: 'Cũ' }],
    aiStatus: 'COMPLETED',
    model: 'fake:scripted',
    promptVersion: 'd1',
    configVersion: 'b-1',
    evidenceStatus: 'PARTIAL',
    errorCode: null,
    errorSummary: null,
    inputTokens: null,
    outputTokens: null,
    revision: 1,
    rootBriefId: 'b1',
    previousRevisionId: null,
    revisions: [{ id: 'b1', revision: 1, triggerType: 'SCHEDULED', status: 'COMPLETED', generatedAt: '2026-11-01T01:00:05Z' }],
    disclaimer: 'Không phải khuyến nghị.',
    ...overrides,
  };
}

describe('BriefDetail', () => {
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

  async function render(value: Brief) {
    const fixture = TestBed.createComponent(BriefDetail);
    fixture.componentRef.setInput('id', value.id);
    fixture.detectChanges();
    http.expectOne(`/api/v1/briefs/${value.id}`).flush(value);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('renders items as plain text with citation buttons and item kinds', async () => {
    const fixture = await render(brief());
    const element: HTMLElement = fixture.nativeElement;

    const items = element.querySelectorAll('.brief-item__text');
    // Source text that looks like HTML stays text.
    expect(items[0].textContent).toContain('<b>Lợi nhuận</b>');
    expect(items[0].querySelector('b')).toBeNull();
    expect(Array.from(items[1].querySelectorAll('button.cite')).map((b) => b.textContent?.trim())).toEqual(['S1', 'S2']);
    expect(element.textContent).toContain('AI interpretation');
    expect(element.textContent).toContain('From source');
    expect(element.textContent).toContain('Partially supported');
  });

  it('opens a citation with its dates and links to the source', async () => {
    const fixture = await render(brief());
    const element: HTMLElement = fixture.nativeElement;

    (element.querySelectorAll('.brief-item__text button.cite')[2] as HTMLButtonElement).click();
    fixture.detectChanges();

    const active = element.querySelector('.source--active')!;
    expect(active.textContent).toContain('Email SSI');
    expect(active.textContent).toContain('Email date');
    expect(active.textContent).toContain('Older research');
    expect(active.querySelector('blockquote')?.textContent).toContain('Email');
    expect(active.querySelector('a')?.getAttribute('href')).toBe('/emails/e1');
    expect(element.querySelector('.sources a')?.getAttribute('href')).toBe('/documents/d1');
    expect(element.textContent).toContain('being indexed');
  });

  it('shows the period, freshness and limitations, and scrolls a clicked citation into view', async () => {
    const fixture = await render(brief());
    vi.useFakeTimers();
    const element: HTMLElement = fixture.nativeElement;

    const facts = element.querySelector('[data-testid=brief-facts]')!;
    expect(facts.textContent).toContain('Asia/Ho_Chi_Minh');
    expect(facts.querySelector('[data-testid=freshness]')?.textContent).toContain('2 source(s) published');
    expect(facts.querySelector('[data-testid=freshness]')?.textContent).toContain('1 older research');
    expect(facts.querySelector('[data-testid=limitations]')?.textContent).toContain('Cũ');
    expect(facts.textContent).toContain('not real-time market data');

    const target = element.querySelector('#source-S2') as HTMLElement;
    const scroll = vi.fn();
    target.scrollIntoView = scroll;
    (element.querySelectorAll('.brief-item__text button.cite')[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    vi.runAllTimers();
    expect(scroll).toHaveBeenCalled();
    expect(target.classList).toContain('source--active');
    vi.useRealTimers();
  });

  it('shows a failed brief and its safe error', async () => {
    const fixture = await render(brief({ status: 'FAILED', sections: [], citations: [], pendingSources: [], errorCode: 'INTERNAL_ERROR', errorSummary: 'Unexpected error' }));

    expect(fixture.nativeElement.textContent).toContain('This generation failed: Unexpected error');
  });

  it('regenerates and reports unchanged inputs', async () => {
    const fixture = await render(brief());
    // Fake timers only after rendering: whenStable() hangs with fake timers.
    vi.useFakeTimers();
    try {
      (fixture.nativeElement.querySelector('.page-header button') as HTMLButtonElement).click();
      http.expectOne('/api/v1/briefs/b1/regenerate').flush({ id: 'j1', status: 'PENDING', attempts: 0, maxAttempts: 3 });
      fixture.detectChanges();
      vi.advanceTimersByTime(2000);
      http.expectOne('/api/v1/briefs/jobs/j1').flush({ id: 'j1', status: 'SUCCEEDED', outcome: 'UNCHANGED', briefId: 'b1' });
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('no new revision was created');
    } finally {
      vi.useRealTimers();
    }
  });
});

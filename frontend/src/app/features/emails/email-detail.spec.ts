import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EmailDetail } from './email-detail';

const email = {
  id: 'e1',
  subject: 'Cơ hội đầu tư',
  senderName: 'Techcom Securities',
  senderAddress: 'noreply@tcbs.com.vn',
  sentAt: '2026-10-28T12:14:40Z',
  importedAt: '2026-10-29T08:00:00Z',
  brokerage: 'TCBS',
  parsingStatus: 'PARSED',
  statistics: { totalLinks: 2, researchLinks: 1, documents: 0, downloading: 0, failed: 0 },
  recipients: [],
  messageId: null,
  languages: ['vi'],
  originalFilename: 'x.eml',
  fileSize: 100,
  contentSha256: 'a'.repeat(64),
  parsingError: null,
  hasHtml: true,
  normalizedText: 'VN-Index: Tăng 0,5%',
  attachments: [],
  sameMessageIdAs: [],
};

const indexed = {
  id: 'k1', sourceType: 'EMAIL', sourceId: 'e1', status: 'INDEXED', statusReason: null, indexVersion: 'v',
  currentVersion: true, chunkCount: 4, pageCount: null, attempts: 1, maxAttempts: 3, lastError: null,
  requestedAt: null, startedAt: null, completedAt: '2026-10-29T08:00:00Z', nextAttemptAt: null,
};

function link(status: string, category = 'STOCK_RESEARCH') {
  return {
    id: category === 'PROMOTION' ? 'l2' : 'l1',
    position: 0,
    displayText: category === 'PROMOTION' ? 'Ưu đãi margin' : 'IMP - Cập nhật KQKD',
    originalUrl: 'https://tracker/x',
    targetUrl: 'https://www.tcbs.com.vn/a.pdf',
    host: 'www.tcbs.com.vn',
    section: '1. Báo cáo phân tích mới',
    language: 'vi',
    category,
    classificationRule: 'stock.ticker',
    tickerHint: 'IMP',
    status,
    discoveredAt: '2026-10-29T08:00:00Z',
    lastAttemptAt: null,
    errorMessage: status === 'FAILED' ? 'The link has expired' : null,
    documentId: status === 'DOWNLOADED' ? 'd1' : null,
    download: null,
  };
}

describe('EmailDetail', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [EmailDetail],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function flushInitial(links: unknown[], fixture?: { detectChanges(): void }): void {
    http.expectOne('/api/v1/emails/e1').flush(email);
    http.expectOne('/api/v1/emails/e1/links').flush(links);
    http.expectOne('/api/v1/emails/e1/documents').flush([]);
    http.expectOne('/api/v1/emails/e1/research-items').flush([]);
    fixture?.detectChanges();
    http.expectOne('/api/v1/knowledge/emails/e1').flush(indexed);
    http.expectOne('/api/v1/automation/workflows/e1').flush({
      id: 'w1',
      sourceType: 'EMAIL',
      sourceId: 'e1',
      status: 'COMPLETED',
      trigger: 'EMAIL_IMPORT',
      processingVersion: 'w1',
      startedAt: null,
      completedAt: null,
      updatedAt: null,
      importedBeforeAutomation: false,
      stages: [],
      matches: [],
      jobs: [],
    });
  }

  async function render() {
    const fixture = TestBed.createComponent(EmailDetail);
    fixture.componentRef.setInput('id', 'e1');
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('hides promotion links by default and shows a retry action for failed downloads', async () => {
    const fixture = await render();
    flushInitial([link('FAILED'), link('SKIPPED', 'PROMOTION')], fixture);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
    expect(el.textContent).toContain('The link has expired');
    expect(el.querySelector('tbody button')?.textContent?.trim()).toBe('Retry');
    expect(el.textContent).toContain('Show navigation & promotion links (1)');
  });

  it('requests a download and polls until the report is stored', async () => {
    const fixture = await render();
    flushInitial([link('READY')], fixture);
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('tbody button')!.click();
    http.expectOne('/api/v1/emails/e1/links/l1/download').flush(link('DOWNLOADING'), { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Downloading');

    vi.advanceTimersByTime(2000);
    http.expectOne('/api/v1/emails/e1/links').flush([link('DOWNLOADED')]);
    http.expectOne('/api/v1/emails/e1/documents').flush([]);
    http.expectOne('/api/v1/emails/e1').flush(email);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Downloaded');
    vi.advanceTimersByTime(5000);
    http.expectNone('/api/v1/emails/e1/links');
  });
});

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EmailInbox } from './email-inbox';

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

describe('EmailInbox', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [EmailInbox],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('shows imported emails with link counts and status', async () => {
    const fixture = TestBed.createComponent(EmailInbox);
    fixture.detectChanges();

    http.expectOne('/api/v1/emails/senders').flush([{ address: 'noreply@tcbs.com.vn', name: 'Techcom Securities', messages: 1 }]);
    http.expectOne((r) => r.url === '/api/v1/emails').flush({
      ...emptyPage,
      totalElements: 1,
      totalPages: 1,
      content: [
        {
          id: 'e1',
          subject: 'Cơ hội đầu tư | 28/10/2026',
          senderName: 'Techcom Securities',
          senderAddress: 'noreply@tcbs.com.vn',
          sentAt: '2026-10-28T12:14:40Z',
          importedAt: '2026-10-29T08:00:00Z',
          brokerage: 'TCBS',
          parsingStatus: 'PARSED',
          statistics: { totalLinks: 34, researchLinks: 22, documents: 1, downloading: 1, failed: 0 },
        },
      ],
    });
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector('tbody tr')!;
    expect(row.textContent).toContain('Cơ hội đầu tư | 28/10/2026');
    expect(row.textContent).toContain('22');
    expect(row.textContent).toContain('Downloading');
  });

  it('debounces subject search and filters by sender', async () => {
    const fixture = TestBed.createComponent(EmailInbox);
    fixture.detectChanges();
    http.expectOne('/api/v1/emails/senders').flush([]);
    http.expectOne((r) => r.url === '/api/v1/emails').flush(emptyPage);

    const search = (fixture.nativeElement as HTMLElement).querySelector('input[type=search]') as HTMLInputElement;
    for (const value of ['C', 'Cơ', 'Cơ hội']) {
      search.value = value;
      search.dispatchEvent(new Event('input'));
    }
    vi.advanceTimersByTime(350);

    const searchRequests = http.match((r) => r.url === '/api/v1/emails');
    expect(searchRequests.length).toBe(1);
    expect(searchRequests[0].request.params.get('q')).toBe('Cơ hội');
    searchRequests[0].flush(emptyPage);

    const select = (fixture.nativeElement as HTMLElement).querySelector('select') as HTMLSelectElement;
    const option = document.createElement('option');
    option.value = 'noreply@tcbs.com.vn';
    select.appendChild(option);
    select.value = 'noreply@tcbs.com.vn';
    select.dispatchEvent(new Event('change'));

    const filtered = http.expectOne((r) => r.url === '/api/v1/emails');
    expect(filtered.request.params.get('sender')).toBe('noreply@tcbs.com.vn');
    filtered.flush(emptyPage);
  });
});

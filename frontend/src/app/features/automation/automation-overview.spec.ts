import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { GmailStatus, OverviewReport } from '../../core/models/automation.model';
import { AutomationOverview } from './automation-overview';

const CONNECTED: GmailStatus = {
  configured: true,
  account: {
    id: 'a1',
    emailAddress: 'investor@example.com',
    status: 'CONNECTED',
    grantedScopes: [],
    connectedAt: '2026-10-01T00:00:00Z',
    initialSyncCompleted: true,
    lastSyncStartedAt: '2026-11-01T02:00:00Z',
    lastSyncSuccessAt: '2026-11-01T02:01:00Z',
    lastErrorCode: null,
    lastError: null,
  },
  running: false,
  lastRun: null,
  importedTotal: 12,
  schedulerEnabled: true,
  syncIntervalMinutes: 30,
  initialLookbackDays: 30,
  lastFailedSyncAt: '2026-10-30T02:00:00Z',
  nextScheduledSyncAt: '2026-11-01T02:30:00Z',
  schedulerConfigured: true,
};

function report(period: OverviewReport['period'] = '24h'): OverviewReport {
  return {
    period,
    from: '2026-10-31T03:00:00Z',
    to: '2026-11-01T03:00:00Z',
    gmail: CONNECTED,
    metrics: {
      emailsImported: 4,
      emailsProcessed: 3,
      reportsDownloaded: 2,
      documentsIndexed: 5,
      assessmentsCreated: 1,
      failedJobs: 1,
      pendingJobs: 6,
      briefsGenerated: 1,
    },
    health: [
      { component: 'GMAIL', state: 'OPERATIONAL', reason: 'Synced recently', lastActivityAt: null, checkedAt: '2026-11-01T03:00:00Z' },
      { component: 'KNOWLEDGE_INDEXING', state: 'DISABLED', reason: 'No embedding model is configured', lastActivityAt: null,
        checkedAt: '2026-11-01T03:00:00Z' },
    ],
  };
}

describe('AutomationOverview', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AutomationOverview],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(status: GmailStatus | null, overview: OverviewReport | 'error', query: Record<string, string> = {}) {
    const fixture = TestBed.createComponent(AutomationOverview);
    for (const [key, value] of Object.entries(query)) {
      fixture.componentRef.setInput(key === 'gmail' ? 'gmail' : key, value);
    }
    fixture.detectChanges();
    http.expectOne('/api/v1/integrations/gmail/status').flush(status);
    http.expectOne('/api/v1/integrations/gmail/sync-history?page=0&size=10').flush({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    const request = http.expectOne('/api/v1/automation/overview?period=24h');
    if (overview === 'error') {
      request.flush({ detail: 'down' }, { status: 503, statusText: 'Unavailable' });
    } else {
      request.flush(overview);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('shows the connection, next sync, labelled metrics and component health', () => {
    const fixture = render(CONNECTED, report());
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('investor@example.com');
    expect(el.textContent).toContain('Every 30 minutes');
    expect(el.querySelector('[data-testid=next-sync]')?.textContent).toContain('within a minute');
    expect(el.querySelector('[data-testid=metrics-window]')?.textContent).toContain('Last 24 hours');
    expect(el.textContent).toContain('Pending jobs (now)');
    const health = Array.from(el.querySelectorAll('.health-list__item')).map((li) => li.textContent);
    expect(health[0]).toContain('Gmail connection');
    expect(health[0]).toContain('Operational');
    expect(health[1]).toContain('No embedding model is configured');
    expect(health[1]).toContain('Disabled');
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Sync now'))).toBe(true);
  });

  it('switches the metrics period', () => {
    const fixture = render(CONNECTED, report());
    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tabs__tab')).find((b) => b.textContent?.trim() === '7d');
    (button as HTMLButtonElement).click();
    http.expectOne('/api/v1/automation/overview?period=7d').flush(report('7d'));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid=metrics-window]')?.textContent).toContain('Last 7 days');
  });

  it('never reports a component as operational when the backend cannot be reached', () => {
    const fixture = render(CONNECTED, 'error');
    const el = fixture.nativeElement as HTMLElement;
    const states = Array.from(el.querySelectorAll('.health-list__item')).map((li) => li.textContent ?? '');
    expect(states).toHaveLength(5);
    expect(states.every((s) => s.includes('Unavailable'))).toBe(true);
    expect(el.querySelector('.alert--error')).not.toBeNull();
  });

  it('explains a missing configuration and shows the OAuth callback error', () => {
    const fixture = render({ ...CONNECTED, configured: false, account: null }, report(), { gmail: 'error', reason: 'ACCESS_DENIED' });
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Gmail is not configured');
    expect(el.textContent).toContain('Access was not granted in Google.');
  });
});

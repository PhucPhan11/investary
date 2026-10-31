import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { GmailStatus, SourceRule, SyncRun } from '../../core/models/automation.model';
import { GmailSync, splitList } from './gmail-sync';

const NOT_CONFIGURED: GmailStatus = {
  configured: false,
  account: null,
  running: false,
  lastRun: null,
  importedTotal: 0,
  schedulerEnabled: true,
  syncIntervalMinutes: 30,
  initialLookbackDays: 30,
};

const RUN: SyncRun = {
  id: 'run1',
  trigger: 'MANUAL',
  mode: 'INITIAL',
  status: 'SUCCEEDED',
  startedAt: '2026-10-31T10:00:00Z',
  finishedAt: '2026-10-31T10:01:00Z',
  listed: 4,
  imported: 2,
  duplicates: 0,
  notImported: 0,
  skipped: 1,
  rejected: 1,
  errorCode: null,
  errorMessage: null,
  warning: null,
};

const CONNECTED: GmailStatus = {
  ...NOT_CONFIGURED,
  configured: true,
  importedTotal: 2,
  lastRun: RUN,
  account: {
    id: 'a1',
    emailAddress: 'investor@example.com',
    status: 'CONNECTED',
    grantedScopes: ['https://www.googleapis.com/auth/gmail.readonly'],
    connectedAt: '2026-10-31T09:00:00Z',
    initialSyncCompleted: true,
    lastSyncStartedAt: '2026-10-31T10:00:00Z',
    lastSyncSuccessAt: '2026-10-31T10:01:00Z',
    lastErrorCode: null,
    lastError: null,
  },
};

const RULE: SourceRule = {
  id: 'r1',
  name: 'TCBS',
  senderAddresses: [],
  senderDomains: ['tcbs.com.vn'],
  subjectKeywords: [],
  gmailQuery: null,
  enabled: true,
  autoImport: true,
  autoPdfDownload: false,
  createdAt: '2026-10-31T09:00:00Z',
  updatedAt: '2026-10-31T09:00:00Z',
};

describe('GmailSync', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [GmailSync],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(status: GmailStatus, rules: SourceRule[] = [], runs: SyncRun[] = [], query: Record<string, string> = {}) {
    const fixture = TestBed.createComponent(GmailSync);
    for (const [key, value] of Object.entries(query)) {
      fixture.componentRef.setInput(key, value);
    }
    fixture.detectChanges();
    http.expectOne('/api/v1/integrations/gmail/status').flush(status);
    http.expectOne('/api/v1/integrations/gmail/sync-history?page=0&size=10')
      .flush({ content: runs, page: 0, size: 10, totalElements: runs.length, totalPages: runs.length ? 1 : 0 });
    http.expectOne('/api/v1/automation/sources').flush(rules);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<GmailSync>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function button(fixture: ComponentFixture<GmailSync>, label: string): HTMLButtonElement {
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    const found = buttons.find((b) => b.textContent?.trim() === label);
    if (!found) throw new Error(`No button ${label}`);
    return found;
  }

  it('explains the missing configuration and offers no connect button', async () => {
    const fixture = await render(NOT_CONFIGURED);
    expect(text(fixture)).toContain('Gmail is not configured');
    expect(text(fixture)).toContain('No sources yet');
    expect(text(fixture)).not.toContain('Connect Gmail');
  });

  it('starts the OAuth flow by navigating to the URL from the backend', async () => {
    const fixture = await render({ ...NOT_CONFIGURED, configured: true });
    const navigate = vi.spyOn(fixture.componentInstance as unknown as { navigateTo(url: string): void }, 'navigateTo')
      .mockImplementation(() => undefined);
    button(fixture, 'Connect Gmail').click();
    const request = http.expectOne('/api/v1/integrations/gmail/connect');
    expect(request.request.method).toBe('POST');
    request.flush({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?state=x' });
    expect(navigate).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?state=x');
  });

  it('shows the connected account, last run and history, and syncs on demand', async () => {
    const fixture = await render(CONNECTED, [RULE], [RUN]);
    expect(text(fixture)).toContain('investor@example.com');
    expect(text(fixture)).toContain('Every 30 minutes');
    expect(text(fixture)).toContain('2 imported');
    expect(text(fixture)).toContain('*@tcbs.com.vn');

    button(fixture, 'Sync now').click();
    http.expectOne('/api/v1/integrations/gmail/sync')
      .flush({ code: 'GMAIL_SYNC_IN_PROGRESS', detail: 'busy' }, { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/v1/integrations/gmail/status').flush(CONNECTED);
    fixture.detectChanges();
    expect(text(fixture)).toContain('already running');
  });

  it('reports the OAuth callback result from the query string', async () => {
    const fixture = await render({ ...NOT_CONFIGURED, configured: true }, [], [], { gmail: 'error', reason: 'SCOPE_NOT_GRANTED' });
    expect(text(fixture)).toContain('Read-only Gmail access was not granted');
  });

  it('asks to reconnect when Google revoked access', async () => {
    const status: GmailStatus = {
      ...CONNECTED,
      account: { ...CONNECTED.account!, status: 'RECONNECT_REQUIRED', lastError: 'invalid_grant' },
    };
    const fixture = await render(status);
    expect(text(fixture)).toContain('Reconnect Gmail');
    expect(text(fixture)).not.toContain('Sync now');
  });

  it('creates a source rule from the form', async () => {
    const fixture = await render(CONNECTED);
    const element = fixture.nativeElement as HTMLElement;
    const set = (name: string, value: string) => {
      const input = element.querySelector(`[name="${name}"]`) as HTMLInputElement | HTMLTextAreaElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    set('name', 'SSI');
    set('senderDomains', 'ssi.com.vn,\n  vndirect.com.vn ');
    set('senderAddresses', 'research@vcbs.com.vn');
    fixture.detectChanges();
    element.querySelector('form')!.dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/v1/automation/sources');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      name: 'SSI',
      senderAddresses: ['research@vcbs.com.vn'],
      senderDomains: ['ssi.com.vn', 'vndirect.com.vn'],
      subjectKeywords: [],
      gmailQuery: null,
      enabled: true,
      autoImport: true,
      autoPdfDownload: false,
    });
    request.flush({ ...RULE, id: 'r2', name: 'SSI' });
    http.expectOne('/api/v1/automation/sources').flush([RULE]);
  });

  it('splits lists on lines and commas', () => {
    expect(splitList(' a@x.vn,\n\n b.vn ,')).toEqual(['a@x.vn', 'b.vn']);
  });
});

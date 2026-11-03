import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SourceRule } from '../../core/models/automation.model';
import { SourceRules, validateRule } from './source-rules';

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
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  warnings: ['NO_SUBJECT_FILTER'],
};

describe('SourceRules', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SourceRules],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(rules: SourceRule[] = [RULE]) {
    const fixture = TestBed.createComponent(SourceRules);
    fixture.detectChanges();
    http.expectOne('/api/v1/automation/sources').flush(rules);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  function component(fixture: { componentInstance: SourceRules }) {
    return fixture.componentInstance as unknown as {
      draft: { name: string; senderDomains: string; senderAddresses: string; autoPdfDownload: boolean };
      acknowledged: boolean;
      save: () => void;
      confirm: (message: string) => boolean;
    };
  }

  it('validates rules like the backend', () => {
    const base = { name: 'X', senderAddresses: '', senderDomains: '', subjectKeywords: '', gmailQuery: '', enabled: true, autoImport: true,
      autoPdfDownload: false };
    expect(validateRule(base)['senderAddresses']).toBe('Add at least one sender address or domain.');
    expect(validateRule({ ...base, senderDomains: 'not a domain' })['senderDomains']).toContain('is not a valid domain');
    expect(validateRule({ ...base, senderAddresses: 'TCBS <a@tcbs.com.vn>' })['senderAddresses']).toContain('not a valid email');
    expect(validateRule({ ...base, name: ' ', senderDomains: 'tcbs.com.vn' })['name']).toBe('Enter a name.');
    expect(validateRule({ ...base, senderDomains: '@TCBS.com.vn' })).toEqual({});
  });

  it('lists rules with their warnings and does not submit an invalid form', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('*@tcbs.com.vn');
    expect(el.textContent).toContain('No subject filter');

    component(fixture).draft.name = 'SSI';
    component(fixture).save();
    fixture.detectChanges();
    http.expectNone('/api/v1/automation/sources');
    expect(el.textContent).toContain('Add at least one sender address or domain.');
  });

  function type(fixture: { nativeElement: HTMLElement; detectChanges: () => void }, name: string, value: string) {
    const input = fixture.nativeElement.querySelector(`[name=${name}]`) as HTMLInputElement | HTMLTextAreaElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('requires an acknowledgement for a public mailbox domain', async () => {
    const fixture = await render([]);
    const c = component(fixture);
    type(fixture, 'name', 'Analyst');
    type(fixture, 'senderDomains', 'gmail.com');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid=broad-warning]')).not.toBeNull();

    c.save();
    const first = http.expectOne({ method: 'POST', url: '/api/v1/automation/sources' });
    expect(first.request.body.acknowledgeBroadRule).toBeUndefined();
    expect(first.request.body.autoPdfDownload).toBe(false);
    first.flush({ code: 'BROAD_SOURCE_RULE', domains: ['gmail.com'] }, { status: 422, statusText: 'Unprocessable' });

    c.acknowledged = true;
    c.save();
    const second = http.expectOne({ method: 'POST', url: '/api/v1/automation/sources' });
    expect(second.request.body.acknowledgeBroadRule).toBe(true);
    second.flush({ ...RULE, id: 'r2', name: 'Analyst' });
    http.expectOne('/api/v1/automation/sources').flush([]);
  });

  it('asks before enabling automatic PDF downloads and toggles a rule', async () => {
    const fixture = await render();
    const c = component(fixture);
    vi.spyOn(c, 'confirm').mockReturnValue(false);
    const box = (fixture.nativeElement as HTMLElement).querySelector('input[name=autoPdfDownload]') as HTMLInputElement;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    expect(c.draft.autoPdfDownload).toBe(false);
    expect(box.checked).toBe(false);

    const disable = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Disable');
    (disable as HTMLButtonElement).click();
    const put = http.expectOne({ method: 'PUT', url: '/api/v1/automation/sources/r1' });
    expect(put.request.body.enabled).toBe(false);
    put.flush({ ...RULE, enabled: false });
    http.expectOne('/api/v1/automation/sources').flush([{ ...RULE, enabled: false }]);
  });
});

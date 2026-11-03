import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SettingField, SettingsSection } from '../../core/models/automation.model';
import { AutomationSettingsPage, validateSection } from './automation-settings';

function field(overrides: Partial<SettingField>): SettingField {
  return {
    name: 'intervalMinutes',
    label: 'Polling interval (minutes)',
    help: null,
    type: 'INTEGER',
    value: 30,
    defaultValue: 30,
    overridden: false,
    min: 5,
    max: 1440,
    options: [],
    nullable: false,
    appliesAfterRestart: false,
    readOnly: false,
    ...overrides,
  };
}

const GMAIL: SettingsSection = {
  section: 'gmail',
  version: 2,
  fields: [
    field({ name: 'pollingEnabled', label: 'Automatic polling', type: 'BOOLEAN', value: true, defaultValue: true, min: null, max: null }),
    field({}),
    field({ name: 'schedulerConfigured', label: 'Scheduler available', type: 'BOOLEAN', value: true, readOnly: true, min: null, max: null }),
  ],
};

const PROCESSING: SettingsSection = {
  section: 'processing',
  version: 0,
  fields: [
    field({ name: 'workerConcurrency', label: 'Background job concurrency', value: 4, defaultValue: 2, overridden: true, min: 1, max: 8,
      appliesAfterRestart: true }),
  ],
};

describe('AutomationSettingsPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AutomationSettingsPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(AutomationSettingsPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/portfolios').flush([]);
    http.expectOne('/api/v1/automation/settings').flush([GMAIL, PROCESSING]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  function section(fixture: { nativeElement: HTMLElement }, name: string): HTMLElement {
    return fixture.nativeElement.querySelector(`[data-section=${name}]`) as HTMLElement;
  }

  it('validates bounds and required values like the backend', () => {
    expect(validateSection(GMAIL.fields, { pollingEnabled: true, intervalMinutes: 2, schedulerConfigured: true })['intervalMinutes'])
      .toBe('Must be between 5 and 1440.');
    expect(validateSection(GMAIL.fields, { pollingEnabled: true, intervalMinutes: null, schedulerConfigured: true })['intervalMinutes'])
      .toBe('Required.');
    expect(validateSection([field({ type: 'TIME', name: 't', min: null, max: null })], { t: '25:00' })['t']).toBe('Enter a time HH:mm.');
    expect(validateSection(GMAIL.fields, { pollingEnabled: false, intervalMinutes: 15, schedulerConfigured: false })).toEqual({});
  });

  it('shows defaults, read-only values and the restart note', async () => {
    const fixture = await render();
    const gmail = section(fixture, 'gmail');
    expect(gmail.textContent).toContain('Default: 30');
    expect(gmail.querySelector('[data-field=schedulerConfigured]')?.textContent).toContain('set in the configuration');
    const processing = section(fixture, 'processing');
    expect(processing.textContent).toContain('Applies after restart');
    expect(processing.textContent).toContain('Changed');
  });

  function type(fixture: { nativeElement: HTMLElement; detectChanges: () => void }, name: string, value: string) {
    const input = fixture.nativeElement.querySelector(`[data-field=${name}] input`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('saves only changed fields with the section version and shows backend field errors', async () => {
    const fixture = await render();
    type(fixture, 'intervalMinutes', '15');
    (section(fixture, 'gmail').querySelector('button[type=submit]') as HTMLButtonElement).click();
    const put = http.expectOne({ method: 'PUT', url: '/api/v1/automation/settings/gmail' });
    expect(put.request.body).toEqual({ version: 2, values: { intervalMinutes: 15 } });
    put.flush({ code: 'INVALID_SETTINGS', errors: { intervalMinutes: 'must be between 5 and 1440' } },
      { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect(section(fixture, 'gmail').textContent).toContain('must be between 5 and 1440');
  });

  it('blocks an out-of-range value before calling the backend', async () => {
    const fixture = await render();
    type(fixture, 'intervalMinutes', '1');
    (section(fixture, 'gmail').querySelector('button[type=submit]') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectNone('/api/v1/automation/settings/gmail');
    expect(section(fixture, 'gmail').textContent).toContain('Must be between 5 and 1440.');
  });

  it('resets an overridden field to its default', async () => {
    const fixture = await render();
    const reset = Array.from(section(fixture, 'processing').querySelectorAll('button')).find((b) => b.textContent?.includes('Reset'));
    (reset as HTMLButtonElement).click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/automation/settings/processing/workerConcurrency' })
      .flush({ ...PROCESSING, version: 1, fields: [{ ...PROCESSING.fields[0], value: 2, overridden: false }] });
    fixture.detectChanges();
    expect(section(fixture, 'processing').textContent).toContain('reset to the configured default');
    expect(section(fixture, 'processing').textContent).not.toContain('Changed');
  });
});

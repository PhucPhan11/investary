import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AutomationCenterApiService } from '../../core/api/automation-api.service';
import { BriefApiService } from '../../core/api/brief-api.service';
import { errorMessage } from '../../core/api/error-message';
import { PortfolioApiService } from '../../core/api/portfolio-api.service';
import { SettingField, SettingValue, SettingsSection } from '../../core/models/automation.model';
import { Portfolio } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';

export const SECTION_TITLES: Record<string, { title: string; description: string }> = {
  gmail: { title: 'Gmail', description: 'Automatic polling of the connected Gmail account.' },
  processing: { title: 'Research processing', description: 'Automatic PDF downloads, retries and background jobs.' },
  brief: { title: 'Daily brief', description: 'When the brief is generated and what it covers.' },
  ai: { title: 'AI usage limits', description: 'Enforced by the backend for automatic and interactive AI calls.' },
};

const FALLBACK_ZONES = ['Asia/Ho_Chi_Minh', 'Asia/Bangkok', 'Asia/Singapore', 'Asia/Tokyo', 'Europe/London', 'Europe/Berlin',
  'America/New_York', 'UTC'];

interface SectionState {
  section: SettingsSection;
  draft: Record<string, SettingValue>;
  errors: Record<string, string>;
  saving: boolean;
  message: string | null;
}

/** Runtime settings: the configured defaults, the user's overrides, bounds and whether a restart is needed. */
@Component({
  selector: 'app-automation-settings',
  imports: [FormsModule, RouterLink, Alert],
  templateUrl: './automation-settings.html',
})
export class AutomationSettingsPage implements OnInit {
  private readonly api = inject(AutomationCenterApiService);
  private readonly briefs = inject(BriefApiService);
  private readonly portfoliosApi = inject(PortfolioApiService);

  protected readonly sections = signal<SectionState[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly portfolios = signal<Portfolio[]>([]);
  protected readonly generatedJob = signal<string | null>(null);
  protected readonly titles = SECTION_TITLES;
  protected readonly zones = supportedZones();

  ngOnInit(): void {
    this.portfoliosApi.list().subscribe({ next: (p) => this.portfolios.set(p), error: () => this.portfolios.set([]) });
    this.load();
  }

  protected load(): void {
    this.api.settings().subscribe({
      next: (sections) => {
        this.sections.set(sections.map((section) => state(section)));
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the settings.'));
      },
    });
  }

  protected changed(s: SectionState): string[] {
    return s.section.fields.filter((f) => !f.readOnly && normalize(f, s.draft[f.name]) !== f.value).map((f) => f.name);
  }

  protected save(s: SectionState): void {
    const errors = validateSection(s.section.fields, s.draft);
    s.errors = errors;
    s.message = null;
    if (Object.keys(errors).length > 0) {
      this.refresh();
      return;
    }
    const values: Record<string, SettingValue> = {};
    for (const name of this.changed(s)) {
      const field = s.section.fields.find((f) => f.name === name)!;
      values[name] = normalize(field, s.draft[name]);
    }
    if (Object.keys(values).length === 0) {
      s.message = 'Nothing changed.';
      this.refresh();
      return;
    }
    s.saving = true;
    this.refresh();
    this.api.saveSettings(s.section.section, s.section.version, values).subscribe({
      next: (section) => this.replace(section, 'Saved. Changes apply without a restart unless marked otherwise.'),
      error: (err: HttpErrorResponse) => {
        s.saving = false;
        if (err.status === 422 && err.error?.errors) {
          s.errors = err.error.errors;
        } else if (err.status === 409) {
          s.message = 'These settings were changed elsewhere. Reload and apply your change again.';
        } else {
          s.message = errorMessage(err, 'Could not save the settings.');
        }
        this.refresh();
      },
    });
  }

  protected reset(s: SectionState, field: SettingField): void {
    this.api.resetSetting(s.section.section, field.name).subscribe({
      next: (section) => this.replace(section, `${field.label} reset to the configured default.`),
      error: (err) => this.error.set(errorMessage(err, 'Could not reset the setting.')),
    });
  }

  protected generateBrief(s: SectionState): void {
    const portfolio = (s.section.fields.find((f) => f.name === 'defaultPortfolioId')?.value as string | null) ?? undefined;
    this.briefs.generate(portfolio).subscribe({
      next: (job) => {
        this.generatedJob.set(job.id);
        this.notice.set('Brief generation was queued. It appears in Daily brief when it is ready.');
      },
      error: (err) => this.error.set(errorMessage(err, 'Could not start the brief generation.')),
    });
  }

  protected display(field: SettingField, value: SettingValue): string {
    if (value === null || value === '') {
      return field.name === 'defaultPortfolioId' ? 'Every portfolio' : 'Not set';
    }
    if (field.type === 'BOOLEAN') {
      return value ? 'On' : 'Off';
    }
    if (field.name === 'defaultPortfolioId') {
      return this.portfolios().find((p) => p.id === value)?.name ?? String(value);
    }
    return String(value);
  }

  private replace(section: SettingsSection, message: string): void {
    this.sections.update((list) => list.map((s) => (s.section.section === section.section ? { ...state(section), message } : s)));
  }

  private refresh(): void {
    this.sections.update((list) => [...list]);
  }
}

function state(section: SettingsSection): SectionState {
  const draft: Record<string, SettingValue> = {};
  section.fields.forEach((f) => (draft[f.name] = f.value));
  return { section, draft, errors: {}, saving: false, message: null };
}

/** Number inputs give numbers or null; empty text means "not set". */
function normalize(field: SettingField, value: SettingValue | undefined): SettingValue {
  if (value === undefined || value === '') {
    return null;
  }
  if (field.type === 'INTEGER' && typeof value === 'string') {
    return Number(value);
  }
  return value;
}

/** Mirrors the backend bounds; the backend validates again. */
export function validateSection(fields: SettingField[], draft: Record<string, SettingValue>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of fields) {
    if (field.readOnly) {
      continue;
    }
    const value = normalize(field, draft[field.name]);
    if (value === null) {
      if (!field.nullable && field.type !== 'BOOLEAN') {
        errors[field.name] = 'Required.';
      }
      continue;
    }
    if (field.type === 'INTEGER') {
      const n = Number(value);
      if (!Number.isInteger(n)) {
        errors[field.name] = 'Enter a whole number.';
      } else if ((field.min !== null && n < field.min) || (field.max !== null && n > field.max)) {
        errors[field.name] = `Must be between ${field.min} and ${field.max}.`;
      }
    } else if (field.type === 'TIME' && !/^([01]?\d|2[0-3]):[0-5]\d$/.test(String(value))) {
      errors[field.name] = 'Enter a time HH:mm.';
    } else if (field.type === 'ENUM' && !field.options.includes(String(value))) {
      errors[field.name] = `Choose one of ${field.options.join(', ')}.`;
    }
  }
  return errors;
}

function supportedZones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  try {
    const zones = intl.supportedValuesOf?.('timeZone') ?? [];
    return zones.length > 0 ? zones : FALLBACK_ZONES;
  } catch {
    return FALLBACK_ZONES;
  }
}

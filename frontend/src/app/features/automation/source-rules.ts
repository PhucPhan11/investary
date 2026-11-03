import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';

import { SourceRuleApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { SourceRule, SourceRuleRequest } from '../../core/models/automation.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';

interface RuleDraft {
  name: string;
  senderAddresses: string;
  senderDomains: string;
  subjectKeywords: string;
  gmailQuery: string;
  enabled: boolean;
  autoImport: boolean;
  autoPdfDownload: boolean;
}

/** Same limits as the backend (SourceRuleService); the backend still validates every request. */
const MAX_SENDERS = 50;
const MAX_KEYWORDS = 20;
const MAX_KEYWORD_LENGTH = 100;
const DOMAIN = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const ADDRESS = /^[^\s@<>()]+@[^\s@<>()]+$/;

/** Public mailbox services: a domain rule for them matches every user of the service. */
export const PUBLIC_MAIL_DOMAINS = new Set([
  'gmail.com', 'googlemail.com', 'yahoo.com', 'yahoo.com.vn', 'outlook.com', 'hotmail.com', 'live.com', 'msn.com', 'icloud.com',
  'me.com', 'aol.com', 'proton.me', 'protonmail.com', 'gmx.com', 'mail.com', 'yandex.com', 'zoho.com',
]);

export const WARNING_TEXT: Record<string, string> = {
  PUBLIC_MAIL_DOMAIN: 'A public mailbox domain matches every sender on that service, not only the brokerage.',
  MANY_DOMAINS: 'Many sender domains in one rule; consider one rule per brokerage.',
  NO_SUBJECT_FILTER: 'Every email from these domains is imported (no subject keyword or Gmail search).',
};

/** Brokerage source rules: which verified senders Gmail sync may import, and what happens next. */
@Component({
  selector: 'app-source-rules',
  imports: [FormsModule, Alert, EmptyState],
  templateUrl: './source-rules.html',
})
export class SourceRules implements OnInit {
  private readonly rulesApi = inject(SourceRuleApiService);

  protected readonly rules = signal<SourceRule[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly editingId = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly errors = signal<Record<string, string>>({});
  /** Set when the backend asked to confirm a public mailbox domain. */
  protected readonly broadDomains = signal<string[] | null>(null);
  protected readonly warningText = WARNING_TEXT;
  protected acknowledged = false;
  protected draft: RuleDraft = emptyDraft();

  ngOnInit(): void {
    this.load();
  }

  protected save(): void {
    const errors = validateRule(this.draft);
    this.errors.set(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }
    const request: SourceRuleRequest = { ...toRequest(this.draft), acknowledgeBroadRule: this.acknowledged || undefined };
    this.saving.set(true);
    this.error.set(null);
    const id = this.editingId();
    (id ? this.rulesApi.update(id, request) : this.rulesApi.create(request)).subscribe({
      next: () => {
        this.saving.set(false);
        this.cancel();
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        if (err.status === 422 && err.error?.code === 'BROAD_SOURCE_RULE') {
          this.broadDomains.set(err.error.domains ?? []);
          return;
        }
        if (err.status === 400 && err.error?.errors) {
          this.errors.set(err.error.errors);
        }
        this.error.set(errorMessage(err, 'Could not save the source rule.'));
      },
    });
  }

  protected edit(rule: SourceRule): void {
    this.editingId.set(rule.id);
    this.errors.set({});
    this.broadDomains.set(null);
    this.acknowledged = false;
    this.draft = {
      name: rule.name,
      senderAddresses: rule.senderAddresses.join('\n'),
      senderDomains: rule.senderDomains.join('\n'),
      subjectKeywords: rule.subjectKeywords.join('\n'),
      gmailQuery: rule.gmailQuery ?? '',
      enabled: rule.enabled,
      autoImport: rule.autoImport,
      autoPdfDownload: rule.autoPdfDownload,
    };
  }

  protected cancel(): void {
    this.editingId.set(null);
    this.errors.set({});
    this.broadDomains.set(null);
    this.acknowledged = false;
    this.draft = emptyDraft();
  }

  /** Automatic PDF download is never switched on silently. */
  protected toggleAutoPdf(event: Event): void {
    const box = event.target as HTMLInputElement;
    if (box.checked && !this.confirm('Download linked PDF reports automatically for this source? Links are still checked by the '
        + 'download policy (https, approved brokerage domains, PDF content only).')) {
      box.checked = false;
      this.draft.autoPdfDownload = false;
      return;
    }
    this.draft.autoPdfDownload = box.checked;
  }

  protected toggleEnabled(rule: SourceRule): void {
    this.rulesApi.update(rule.id, { ...ruleRequest(rule), enabled: !rule.enabled }).subscribe({
      next: () => this.load(),
      error: (err) => this.error.set(errorMessage(err, 'Could not change the source.')),
    });
  }

  protected remove(rule: SourceRule): void {
    if (!this.confirm(`Delete the source rule "${rule.name}"? Emails already imported stay.`)) {
      return;
    }
    this.rulesApi.delete(rule.id).subscribe({
      next: () => this.load(),
      error: (err) => this.error.set(errorMessage(err, 'Could not delete the source rule.')),
    });
  }

  protected senders(rule: SourceRule): string {
    return [...rule.senderAddresses, ...rule.senderDomains.map((d) => `*@${d}`)].join(', ');
  }

  /** Domains in the draft that are public mailbox services (warned before saving). */
  protected publicDomains(): string[] {
    return splitList(this.draft.senderDomains).map(normalizeDomain).filter((d) => PUBLIC_MAIL_DOMAINS.has(d));
  }

  /** Test seam for confirmation dialogs. */
  protected confirm(message: string): boolean {
    return window.confirm(message);
  }

  private load(): void {
    this.rulesApi.list().subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the source rules.'));
      },
    });
  }
}

/** Client-side checks that mirror the backend; returns field → message. */
export function validateRule(draft: RuleDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!draft.name.trim()) {
    errors['name'] = 'Enter a name.';
  } else if (draft.name.trim().length > 100) {
    errors['name'] = 'At most 100 characters.';
  }
  const addresses = splitList(draft.senderAddresses).map((a) => a.toLowerCase());
  const domains = splitList(draft.senderDomains).map(normalizeDomain);
  const badAddress = addresses.find((a) => !ADDRESS.test(a) || !DOMAIN.test(a.split('@')[1]));
  if (badAddress) {
    errors['senderAddresses'] = `'${badAddress}' is not a valid email address.`;
  } else if (addresses.length > MAX_SENDERS) {
    errors['senderAddresses'] = `At most ${MAX_SENDERS} addresses.`;
  }
  const badDomain = domains.find((d) => !DOMAIN.test(d));
  if (badDomain) {
    errors['senderDomains'] = `'${badDomain}' is not a valid domain.`;
  } else if (domains.length > MAX_SENDERS) {
    errors['senderDomains'] = `At most ${MAX_SENDERS} domains.`;
  }
  if (addresses.length === 0 && domains.length === 0) {
    errors['senderAddresses'] = 'Add at least one sender address or domain.';
  }
  const keywords = splitList(draft.subjectKeywords);
  if (keywords.length > MAX_KEYWORDS) {
    errors['subjectKeywords'] = `At most ${MAX_KEYWORDS} keywords.`;
  } else if (keywords.some((k) => k.length > MAX_KEYWORD_LENGTH)) {
    errors['subjectKeywords'] = `Keywords must be at most ${MAX_KEYWORD_LENGTH} characters.`;
  }
  if (draft.gmailQuery.length > 500) {
    errors['gmailQuery'] = 'At most 500 characters.';
  }
  return errors;
}

/** One entry per line or comma; blanks dropped. */
export function splitList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((v) => v.trim())
    .filter((v) => v.length > 0);
}

function normalizeDomain(value: string): string {
  return value.toLowerCase().replace(/^@/, '');
}

function toRequest(draft: RuleDraft): SourceRuleRequest {
  return {
    name: draft.name.trim(),
    senderAddresses: splitList(draft.senderAddresses),
    senderDomains: splitList(draft.senderDomains),
    subjectKeywords: splitList(draft.subjectKeywords),
    gmailQuery: draft.gmailQuery.trim() || null,
    enabled: draft.enabled,
    autoImport: draft.autoImport,
    autoPdfDownload: draft.autoPdfDownload,
  };
}

function ruleRequest(rule: SourceRule): SourceRuleRequest {
  return {
    name: rule.name,
    senderAddresses: rule.senderAddresses,
    senderDomains: rule.senderDomains,
    subjectKeywords: rule.subjectKeywords,
    gmailQuery: rule.gmailQuery,
    enabled: rule.enabled,
    autoImport: rule.autoImport,
    autoPdfDownload: rule.autoPdfDownload,
  };
}

function emptyDraft(): RuleDraft {
  return {
    name: '',
    senderAddresses: '',
    senderDomains: '',
    subjectKeywords: '',
    gmailQuery: '',
    enabled: true,
    autoImport: true,
    autoPdfDownload: false,
  };
}

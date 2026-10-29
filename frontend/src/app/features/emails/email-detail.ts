import { DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, effect, inject, input, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { DocumentApiService } from '../../core/api/document-api.service';
import { EmailApiService } from '../../core/api/email-api.service';
import { errorMessage } from '../../core/api/error-message';
import {
  EmailDetail as EmailDetailModel,
  LinkedDocument,
  ResearchItem,
  ResearchItemType,
  SourceLink,
} from '../../core/models/email.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { IndexStatus } from '../../shared/components/index-status';
import { StatusBadge } from '../../shared/components/status-badge';
import { FileSizePipe } from '../../shared/pipes/file-size.pipe';
import { ReportTypeLabelPipe } from '../../shared/pipes/report-type-label.pipe';

const POLL_INTERVAL_MS = 2000;
const HIDDEN_BY_DEFAULT = new Set(['NAVIGATION', 'PROMOTION']);

export const ITEM_TYPE_LABELS: Record<ResearchItemType, string> = {
  MARKET_UPDATE: 'Market update',
  MACRO_UPDATE: 'Macro update',
  INDUSTRY_UPDATE: 'Industry update',
  COMPANY_NEWS: 'Company news',
  STOCK_RECOMMENDATION: 'Stock recommendation (source)',
};

@Component({
  selector: 'app-email-detail',
  imports: [DatePipe, RouterLink, Alert, EmptyState, IndexStatus, StatusBadge, FileSizePipe, ReportTypeLabelPipe],
  templateUrl: './email-detail.html',
})
export class EmailDetail implements OnDestroy {
  private readonly api = inject(EmailApiService);
  private readonly documentsApi = inject(DocumentApiService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly router = inject(Router);
  private pollTimer: ReturnType<typeof setTimeout> | undefined;

  /** Route parameter, bound by the router. */
  readonly id = input.required<string>();

  protected readonly email = signal<EmailDetailModel | null>(null);
  protected readonly links = signal<SourceLink[]>([]);
  protected readonly documents = signal<LinkedDocument[]>([]);
  protected readonly items = signal<ResearchItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly tab = signal<'text' | 'html'>('text');
  protected readonly showAllLinks = signal(false);
  protected readonly requesting = signal<Set<string>>(new Set());

  protected readonly visibleLinks = computed(() =>
    this.showAllLinks() ? this.links() : this.links().filter((l) => !HIDDEN_BY_DEFAULT.has(l.category)),
  );
  protected readonly hiddenCount = computed(() => this.links().length - this.links().filter((l) => !HIDDEN_BY_DEFAULT.has(l.category)).length);
  protected readonly htmlUrl = computed<SafeResourceUrl>(() =>
    // Our own endpoint: sanitized server-side, strict CSP, and rendered in a sandbox without scripts.
    this.sanitizer.bypassSecurityTrustResourceUrl(this.api.htmlUrl(this.id())),
  );
  protected readonly itemTypeLabels = ITEM_TYPE_LABELS;

  constructor() {
    effect(() => this.load(this.id()));
  }

  ngOnDestroy(): void {
    clearTimeout(this.pollTimer);
  }

  protected load(id: string): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      email: this.api.get(id),
      links: this.api.links(id),
      documents: this.api.documents(id),
      items: this.api.researchItems(id),
    }).subscribe({
      next: ({ email, links, documents, items }) => {
        this.email.set(email);
        this.links.set(links);
        this.documents.set(documents);
        this.items.set(items);
        this.loading.set(false);
        this.schedulePoll();
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load the email.'));
        this.loading.set(false);
      },
    });
  }

  protected download(link: SourceLink): void {
    this.setRequesting(link.id, true);
    this.notice.set(null);
    this.api.requestDownload(this.id(), link.id).subscribe({
      next: (updated) => {
        this.setRequesting(link.id, false);
        this.replaceLink(updated);
        this.schedulePoll();
      },
      error: (err) => {
        this.setRequesting(link.id, false);
        this.notice.set(errorMessage(err, 'The download could not be started.'));
      },
    });
  }

  protected canDownload(link: SourceLink): boolean {
    return link.status === 'READY' || link.status === 'DISCOVERED' || link.status === 'FAILED' || link.status === 'SKIPPED';
  }

  protected isRequesting(link: SourceLink): boolean {
    return this.requesting().has(link.id);
  }

  protected downloadUrl(documentId: string): string {
    return this.documentsApi.downloadUrl(documentId);
  }

  protected deleteEmail(): void {
    const email = this.email();
    if (!email || !confirm(`Delete "${email.subject || 'this email'}"? Downloaded reports stay in the document library.`)) {
      return;
    }
    this.api.delete(email.id).subscribe({
      next: () => this.router.navigate(['/emails']),
      error: (err) => this.error.set(errorMessage(err, 'Could not delete the email.')),
    });
  }

  protected recommendation(item: ResearchItem): string | null {
    const value = item.structuredData?.['sourceRecommendation'];
    return typeof value === 'string' ? value : null;
  }

  protected translations(item: ResearchItem): { language: string; recommendation: string | null }[] {
    const translations = item.structuredData?.['translations'] as Record<string, { sourceRecommendation?: string }> | undefined;
    return translations
      ? Object.entries(translations).map(([language, t]) => ({ language, recommendation: t.sourceRecommendation ?? null }))
      : [];
  }

  /** While any download is queued or running, refresh links and documents every few seconds. */
  private schedulePoll(): void {
    clearTimeout(this.pollTimer);
    if (!this.links().some((l) => l.status === 'DOWNLOADING')) {
      return;
    }
    this.pollTimer = setTimeout(() => {
      const id = this.id();
      forkJoin({ links: this.api.links(id), documents: this.api.documents(id), email: this.api.get(id) }).subscribe({
        next: ({ links, documents, email }) => {
          this.links.set(links);
          this.documents.set(documents);
          this.email.set(email);
          this.schedulePoll();
        },
        error: () => this.schedulePoll(),
      });
    }, POLL_INTERVAL_MS);
  }

  private replaceLink(updated: SourceLink): void {
    this.links.update((links) => links.map((l) => (l.id === updated.id ? updated : l)));
  }

  private setRequesting(linkId: string, active: boolean): void {
    this.requesting.update((current) => {
      const next = new Set(current);
      if (active) {
        next.add(linkId);
      } else {
        next.delete(linkId);
      }
      return next;
    });
  }
}

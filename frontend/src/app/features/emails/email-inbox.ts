import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { EmailApiService } from '../../core/api/email-api.service';
import { errorMessage } from '../../core/api/error-message';
import { Page } from '../../core/models/document.model';
import { EmailDetail, EmailSummary, Sender } from '../../core/models/email.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';
import { EmailImportPanel } from './email-import-panel';

const PAGE_SIZE = 20;
const SEARCH_DEBOUNCE_MS = 300;

@Component({
  selector: 'app-email-inbox',
  imports: [DatePipe, RouterLink, Alert, EmptyState, Pager, StatusBadge, EmailImportPanel],
  templateUrl: './email-inbox.html',
})
export class EmailInbox implements OnInit, OnDestroy {
  private readonly api = inject(EmailApiService);
  private readonly router = inject(Router);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  protected readonly page = signal<Page<EmailSummary> | null>(null);
  protected readonly senders = signal<Sender[]>([]);
  protected readonly query = signal('');
  protected readonly sender = signal('');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);
  protected readonly deletingId = signal<string | null>(null);

  ngOnInit(): void {
    this.load(0);
    this.loadSenders();
  }

  ngOnDestroy(): void {
    clearTimeout(this.searchTimer);
  }

  protected onSearch(value: string): void {
    this.query.set(value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(0), SEARCH_DEBOUNCE_MS);
  }

  protected onSenderChange(value: string): void {
    this.sender.set(value);
    this.load(0);
  }

  protected load(pageIndex: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list({ q: this.query(), sender: this.sender(), page: pageIndex, size: PAGE_SIZE }).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load emails.'));
        this.loading.set(false);
      },
    });
  }

  protected onImported(email: EmailDetail): void {
    this.loadSenders();
    this.router.navigate(['/emails', email.id]);
  }

  protected remove(email: EmailSummary): void {
    const title = email.subject || 'this email';
    if (!confirm(`Delete "${title}"? Downloaded reports stay in the document library.`)) {
      return;
    }
    this.deletingId.set(email.id);
    this.success.set(null);
    this.api.delete(email.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.success.set(`"${title}" was deleted. Its reports remain in the document library.`);
        const current = this.page();
        const pageIndex = current && current.content.length === 1 && current.page > 0 ? current.page - 1 : (current?.page ?? 0);
        this.load(pageIndex);
        this.loadSenders();
      },
      error: (err) => {
        this.deletingId.set(null);
        this.error.set(errorMessage(err, 'Could not delete the email.'));
      },
    });
  }

  protected processing(email: EmailSummary): string {
    if (email.statistics.downloading > 0) {
      return 'DOWNLOADING';
    }
    if (email.statistics.failed > 0) {
      return 'FAILED';
    }
    return email.parsingStatus;
  }

  private loadSenders(): void {
    this.api.senders().subscribe({ next: (senders) => this.senders.set(senders), error: () => this.senders.set([]) });
  }
}

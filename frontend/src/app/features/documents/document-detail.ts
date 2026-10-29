import { DatePipe } from '@angular/common';
import { Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { DocumentApiService } from '../../core/api/document-api.service';
import { EmailApiService } from '../../core/api/email-api.service';
import { errorMessage } from '../../core/api/error-message';
import { ResearchDocument } from '../../core/models/document.model';
import { EmailSummary } from '../../core/models/email.model';
import { Alert } from '../../shared/components/alert';
import { FileSizePipe } from '../../shared/pipes/file-size.pipe';
import { ReportTypeLabelPipe } from '../../shared/pipes/report-type-label.pipe';

@Component({
  selector: 'app-document-detail',
  imports: [DatePipe, RouterLink, Alert, FileSizePipe, ReportTypeLabelPipe],
  template: `
    <a routerLink="/documents" class="link back-link">← Document library</a>
    @if (error(); as message) {
      <app-alert kind="error">{{ message }}</app-alert>
    }
    @if (document(); as d) {
      <section class="card detail-header">
        <div class="detail-header__main">
          <h1 class="page-title">{{ d.originalFilename }}</h1>
          <dl class="meta">
            <div><dt>Brokerage</dt><dd>{{ d.brokerageCompany || '—' }}</dd></div>
            <div><dt>Report type</dt><dd>{{ d.reportType | reportTypeLabel }}</dd></div>
            <div><dt>Ticker</dt><dd>{{ d.ticker || '—' }}</dd></div>
            <div><dt>Published</dt><dd>{{ d.publishedOn ? (d.publishedOn | date: 'mediumDate') : '—' }}</dd></div>
            <div><dt>Uploaded</dt><dd>{{ d.uploadedAt | date: 'medium' }}</dd></div>
            <div><dt>Size</dt><dd>{{ d.fileSize | fileSize }}</dd></div>
          </dl>
          <p class="muted small mono">SHA-256 {{ d.sha256Checksum }}</p>
        </div>
        <div class="detail-header__actions">
          <a class="btn btn--primary btn--small" [href]="downloadUrl(d.id)" download>Download PDF</a>
        </div>
      </section>

      <section class="card">
        <div class="card__header">
          <h2 class="card__title">Source emails</h2>
          <span class="muted">Where this report came from</span>
        </div>
        @if (sources().length === 0) {
          <p class="muted">Uploaded directly — not linked to an imported email.</p>
        } @else {
          <ul class="recent-list">
            @for (email of sources(); track email.id) {
              <li class="recent-list__item">
                <div class="recent-list__main">
                  <a class="recent-list__name link-plain" [routerLink]="['/emails', email.id]">{{ email.subject || '(no subject)' }}</a>
                  <span class="recent-list__meta">{{ email.senderName || email.senderAddress }}</span>
                </div>
                <div class="recent-list__side">{{ (email.sentAt ?? email.importedAt) | date: 'mediumDate' }}</div>
              </li>
            }
          </ul>
        }
      </section>
    } @else if (!error()) {
      <p class="muted">Loading document…</p>
    }
  `,
})
export class DocumentDetail {
  private readonly documents = inject(DocumentApiService);
  private readonly emails = inject(EmailApiService);

  /** Route parameter, bound by the router. */
  readonly id = input.required<string>();

  protected readonly document = signal<ResearchDocument | null>(null);
  protected readonly sources = signal<EmailSummary[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.id();
      forkJoin({ document: this.documents.get(id), sources: this.emails.list({ documentId: id, size: 50 }) }).subscribe({
        next: ({ document, sources }) => {
          this.document.set(document);
          this.sources.set(sources.content);
        },
        error: (err) => this.error.set(errorMessage(err, 'Could not load the document.')),
      });
    });
  }

  protected downloadUrl(id: string): string {
    return this.documents.downloadUrl(id);
  }
}

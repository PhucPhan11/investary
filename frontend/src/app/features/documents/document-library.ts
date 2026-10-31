import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { DocumentApiService } from '../../core/api/document-api.service';
import { errorMessage } from '../../core/api/error-message';
import { Page, ResearchDocument } from '../../core/models/document.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { FileSizePipe } from '../../shared/pipes/file-size.pipe';
import { ReportTypeLabelPipe } from '../../shared/pipes/report-type-label.pipe';
import { UploadPanel } from './upload-panel';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-document-library',
  imports: [DatePipe, RouterLink, Alert, EmptyState, Pager, FileSizePipe, ReportTypeLabelPipe, UploadPanel],
  templateUrl: './document-library.html',
})
export class DocumentLibrary implements OnInit {
  protected readonly api = inject(DocumentApiService);

  protected readonly page = signal<Page<ResearchDocument> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);
  protected readonly deletingId = signal<string | null>(null);

  ngOnInit(): void {
    this.load(0);
  }

  protected load(pageIndex: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list(pageIndex, PAGE_SIZE).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load documents.'));
        this.loading.set(false);
      },
    });
  }

  protected onUploaded(document: ResearchDocument): void {
    this.success.set(`"${document.originalFilename}" was uploaded.`);
    this.load(0);
  }

  protected remove(document: ResearchDocument): void {
    if (!confirm(`Delete "${document.originalFilename}"? This cannot be undone.`)) {
      return;
    }
    this.deletingId.set(document.id);
    this.success.set(null);
    this.error.set(null);
    this.api.delete(document.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.success.set(`"${document.originalFilename}" was deleted.`);
        const current = this.page();
        // Step back a page when the last item on a non-first page was removed.
        const pageIndex = current && current.content.length === 1 && current.page > 0 ? current.page - 1 : (current?.page ?? 0);
        this.load(pageIndex);
      },
      error: (err) => {
        this.deletingId.set(null);
        this.error.set(errorMessage(err, 'Could not delete the document.'));
      },
    });
  }
}

import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { DocumentApiService } from '../../core/api/document-api.service';
import { errorMessage } from '../../core/api/error-message';
import { Page, ResearchDocument } from '../../core/models/document.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { FileSizePipe } from '../../shared/pipes/file-size.pipe';
import { ReportTypeLabelPipe } from '../../shared/pipes/report-type-label.pipe';

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, RouterLink, Alert, EmptyState, FileSizePipe, ReportTypeLabelPipe],
  templateUrl: './dashboard.html',
})
export class Dashboard implements OnInit {
  private readonly api = inject(DocumentApiService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal<Page<ResearchDocument> | null>(null);

  protected readonly recent = computed(() => (this.page()?.content ?? []).slice(0, 5));
  protected readonly uploadedThisWeek = computed(() => {
    const cutoff = Date.now() - SEVEN_DAYS_MS;
    return (this.page()?.content ?? []).filter((d) => Date.parse(d.uploadedAt) >= cutoff).length;
  });
  protected readonly brokerageCount = computed(() => {
    const names = (this.page()?.content ?? [])
      .map((d) => d.brokerageCompany?.trim().toLowerCase())
      .filter((name): name is string => !!name);
    return new Set(names).size;
  });

  ngOnInit(): void {
    // The 100 most recent documents are enough for the Phase 1 overview figures.
    this.api.list(0, 100).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }
}

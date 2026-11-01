import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AutomationCenterApiService, SourceRuleApiService } from '../../core/api/automation-api.service';
import { BriefApiService } from '../../core/api/brief-api.service';
import { errorMessage } from '../../core/api/error-message';
import { AutomationJob, AutomationJobStatus, AutomationJobType, JobFilters, SourceRule } from '../../core/models/automation.model';
import { BriefJob } from '../../core/models/brief.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';

export const JOB_TYPE_LABELS: Record<AutomationJobType, string> = {
  PREPARE: 'Read import',
  DOWNLOADS: 'PDF downloads',
  INDEX: 'Indexing',
  MATCH: 'Portfolio matching',
  ASSESS: 'Impact assessment',
};

export const RETRY_BLOCKED: Record<string, string> = {
  NOT_FAILED: 'Only failed jobs can be retried.',
  SOURCE_DELETED: 'The source was deleted; a retry cannot succeed.',
  RETRY_LIMIT_REACHED: 'The manual retry limit was reached; process the source again instead.',
};

const PAGE_SIZE = 20;

/** Research-processing and brief-generation job history with filters and safe retry. */
@Component({
  selector: 'app-job-history',
  imports: [DatePipe, FormsModule, RouterLink, Alert, EmptyState, Pager, StatusBadge],
  templateUrl: './job-history.html',
})
export class JobHistory implements OnInit {
  private readonly api = inject(AutomationCenterApiService);
  private readonly briefsApi = inject(BriefApiService);
  private readonly rulesApi = inject(SourceRuleApiService);

  /** Initial status filter from the address bar (e.g. from the overview's failed-jobs tile). */
  readonly status = input<string | undefined>();

  protected readonly kind = signal<'research' | 'brief'>('research');
  protected readonly jobs = signal<AutomationJob[]>([]);
  protected readonly briefJobs = signal<BriefJob[]>([]);
  protected readonly rules = signal<SourceRule[]>([]);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly retrying = signal<string | null>(null);
  protected readonly typeLabels = JOB_TYPE_LABELS;
  protected readonly blockedText = RETRY_BLOCKED;
  protected readonly statuses: AutomationJobStatus[] = ['PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED'];
  protected readonly types = Object.keys(JOB_TYPE_LABELS) as AutomationJobType[];
  protected filters: Required<JobFilters> = { status: '', type: '', sourceType: '', ruleId: '', from: '', to: '' };

  ngOnInit(): void {
    const initial = this.status();
    if (initial && (this.statuses as string[]).includes(initial)) {
      this.filters.status = initial as AutomationJobStatus;
    }
    this.rulesApi.list().subscribe({ next: (rules) => this.rules.set(rules), error: () => this.rules.set([]) });
    this.load(0);
  }

  protected switchKind(kind: 'research' | 'brief'): void {
    this.kind.set(kind);
    this.load(0);
  }

  protected applyFilters(): void {
    if (this.filters.from && this.filters.to && this.filters.from > this.filters.to) {
      this.error.set('The start date must not be after the end date.');
      return;
    }
    this.load(0);
  }

  protected clearFilters(): void {
    this.filters = { status: '', type: '', sourceType: '', ruleId: '', from: '', to: '' };
    this.load(0);
  }

  protected load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    if (this.kind() === 'brief') {
      const status = this.filters.status === 'WAITING' ? '' : (this.filters.status as BriefJob['status'] | '');
      this.briefsApi.jobs(status, page, PAGE_SIZE, this.filters.from, this.filters.to).subscribe({
        next: (result) => {
          this.briefJobs.set(result.content);
          this.paging(result.page, result.totalPages, result.totalElements);
        },
        error: (err) => this.failed(err),
      });
      return;
    }
    this.api.jobs(this.filters, page, PAGE_SIZE).subscribe({
      next: (result) => {
        this.jobs.set(result.content);
        this.paging(result.page, result.totalPages, result.totalElements);
      },
      error: (err) => this.failed(err),
    });
  }

  protected retry(job: AutomationJob): void {
    this.retrying.set(job.id);
    this.api.retry(job.id).subscribe({
      next: () => {
        this.retrying.set(null);
        this.notice.set(`Retry of the ${JOB_TYPE_LABELS[job.type].toLowerCase()} job was queued.`);
        this.load(this.page());
      },
      error: (err) => {
        this.retrying.set(null);
        this.load(this.page());
        this.error.set(errorMessage(err, 'The job could not be retried.'));
      },
    });
  }

  protected retryBrief(job: BriefJob): void {
    this.retrying.set(job.id);
    this.briefsApi.retryJob(job.id).subscribe({
      next: () => {
        this.retrying.set(null);
        this.notice.set(`Brief generation for ${job.businessDate} was queued again.`);
        this.load(this.page());
      },
      error: (err) => {
        this.retrying.set(null);
        this.load(this.page());
        this.error.set(errorMessage(err, 'The brief job could not be retried.'));
      },
    });
  }

  protected sourceLink(job: AutomationJob): string[] | null {
    if (!job.sourceId || !job.sourceTitle) {
      return null;
    }
    return job.sourceType === 'DOCUMENT' ? ['/documents', job.sourceId] : ['/emails', job.sourceId];
  }

  private paging(page: number, totalPages: number, total: number): void {
    this.page.set(page);
    this.totalPages.set(totalPages);
    this.total.set(total);
    this.loading.set(false);
  }

  private failed(err: unknown): void {
    this.loading.set(false);
    this.error.set(errorMessage(err, 'Could not load the jobs.'));
  }
}

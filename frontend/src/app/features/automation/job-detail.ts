import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AutomationCenterApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { JobDetail } from '../../core/models/automation.model';
import { Alert } from '../../shared/components/alert';
import { StatusBadge } from '../../shared/components/status-badge';
import { REASONS, STAGE_LABELS } from '../../shared/components/workflow-status';
import { JOB_TYPE_LABELS, RETRY_BLOCKED } from './job-history';

const POLL_MS = 3000;
const ACTIVE = new Set(['PENDING', 'RUNNING', 'WAITING']);

/** One research-processing job: its workflow stages, attempt history, related source and a safe retry. */
@Component({
  selector: 'app-job-detail',
  imports: [DatePipe, RouterLink, Alert, StatusBadge],
  templateUrl: './job-detail.html',
})
export class JobDetailPage {
  private readonly api = inject(AutomationCenterApiService);
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  readonly id = input.required<string>();

  protected readonly detail = signal<JobDetail | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly retrying = signal(false);
  protected readonly typeLabels = JOB_TYPE_LABELS;
  protected readonly stageLabels = STAGE_LABELS;
  protected readonly reasons = REASONS;
  protected readonly blockedText = RETRY_BLOCKED;

  protected readonly stages = computed(() => (this.detail()?.workflow?.stages ?? []).filter((s) => s.stage !== 'COMPLETED'));
  protected readonly sourceLink = computed(() => {
    const job = this.detail()?.job;
    if (!job?.sourceId || !job.sourceTitle) {
      return null;
    }
    return job.sourceType === 'DOCUMENT' ? ['/documents', job.sourceId] : ['/emails', job.sourceId];
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
    effect(() => this.load(this.id()));
  }

  protected retry(): void {
    const job = this.detail()?.job;
    if (!job) {
      return;
    }
    this.retrying.set(true);
    this.error.set(null);
    this.api.retry(job.id).subscribe({
      next: () => {
        this.retrying.set(false);
        this.load(job.id);
      },
      error: (err) => {
        this.retrying.set(false);
        this.error.set(errorMessage(err, 'The job could not be retried.'));
        this.load(job.id);
      },
    });
  }

  private load(id: string): void {
    this.stopPolling();
    this.api.job(id).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.loading.set(false);
        if (ACTIVE.has(detail.job.status)) {
          this.pollTimer = setTimeout(() => this.load(id), POLL_MS);
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the job.'));
      },
    });
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }
}

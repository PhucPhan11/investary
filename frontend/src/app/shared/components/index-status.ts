import { DatePipe } from '@angular/common';
import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';

import { errorMessage } from '../../core/api/error-message';
import { KnowledgeApiService } from '../../core/api/knowledge-api.service';
import { KnowledgeJob, KnowledgeSourceType } from '../../core/models/knowledge.model';
import { StatusBadge } from './status-badge';

const POLL_MS = 3000;
const REASONS: Record<string, string> = {
  OCR_REQUIRED: 'The PDF has no text layer (scanned). OCR is not supported yet.',
  NO_TEXT: 'No indexable text was found.',
  PARSING_FAILED: 'The email could not be parsed, so there is no reliable text to index.',
};

/**
 * Knowledge base state of one document or email, with retry / reindex actions. Polls while a job is
 * queued or running.
 */
@Component({
  selector: 'app-index-status',
  imports: [DatePipe, StatusBadge],
  template: `
    <section class="card index-status">
      <div class="card__header">
        <h2 class="card__title">Research assistant index</h2>
        @if (job(); as j) {
          <app-status-badge [value]="j.status" />
        }
      </div>
      @if (error(); as message) {
        <p class="link-error">{{ message }}</p>
      }
      @if (job(); as j) {
        <p class="muted small">
          @switch (j.status) {
            @case ('NOT_INDEXED') { Not in the knowledge base yet. }
            @case ('PENDING') { Queued for indexing. Indexing runs in the background when an embedding model is configured. }
            @case ('PROCESSING') { Extracting text and creating embeddings… }
            @case ('INDEXED') {
              {{ j.chunkCount }} searchable passage{{ j.chunkCount === 1 ? '' : 's' }}
              @if (j.pageCount) { from {{ j.pageCount }} page{{ j.pageCount === 1 ? '' : 's' }} }
              @if (j.completedAt) { · indexed {{ j.completedAt | date: 'medium' }} }
            }
            @case ('SKIPPED') { {{ reason(j.statusReason) }} }
            @case ('FAILED') { Indexing failed after {{ j.attempts }} attempt{{ j.attempts === 1 ? '' : 's' }}. }
          }
        </p>
        @if (j.status === 'INDEXED' && !j.currentVersion) {
          <p class="muted small">Indexed with an older configuration, so it is not searched. Reindex to include it.</p>
        }
        @if (j.lastError && j.status !== 'INDEXED') {
          <p class="link-error small">{{ j.lastError }}</p>
        }
        <div class="actions">
          @switch (j.status) {
            @case ('NOT_INDEXED') {
              <button type="button" class="btn btn--small btn--primary" [disabled]="busy()" (click)="index()">Index now</button>
            }
            @case ('FAILED') {
              <button type="button" class="btn btn--small btn--primary" [disabled]="busy()" (click)="index()">Retry</button>
            }
            @case ('INDEXED') {
              <button type="button" class="btn btn--small" [disabled]="busy()" (click)="index()">Reindex</button>
            }
            @case ('SKIPPED') {
              <button type="button" class="btn btn--small" [disabled]="busy()" (click)="index()">Reindex</button>
            }
          }
        </div>
      } @else if (!error()) {
        <p class="muted small">Loading index status…</p>
      }
    </section>
  `,
})
export class IndexStatus {
  private readonly api = inject(KnowledgeApiService);

  readonly sourceType = input.required<KnowledgeSourceType>();
  readonly sourceId = input.required<string>();

  protected readonly job = signal<KnowledgeJob | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => this.load(this.sourceType(), this.sourceId()));
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  protected index(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.index(this.sourceType(), this.sourceId()).subscribe({
      next: (job) => {
        this.busy.set(false);
        this.show(job);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not queue indexing.'));
      },
    });
  }

  protected reason(code: string | null): string {
    return (code && REASONS[code]) ?? 'Nothing to index.';
  }

  private load(type: KnowledgeSourceType, id: string): void {
    this.stopPolling();
    this.api.sourceStatus(type, id).subscribe({
      next: (job) => this.show(job),
      error: (err) => this.error.set(errorMessage(err, 'Could not load the index status.')),
    });
  }

  private show(job: KnowledgeJob): void {
    this.job.set(job);
    this.stopPolling();
    if (job.status === 'PENDING' || job.status === 'PROCESSING') {
      this.timer = setTimeout(() => this.load(this.sourceType(), this.sourceId()), POLL_MS);
    }
  }

  private stopPolling(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}

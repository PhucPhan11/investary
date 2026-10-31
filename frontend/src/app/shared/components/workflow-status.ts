import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';

import { WorkflowApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { ResearchWorkflow, WorkflowStageName } from '../../core/models/automation.model';
import { StatusBadge } from './status-badge';

const POLL_MS = 3000;

const STAGE_LABELS: Record<WorkflowStageName, string> = {
  EMAIL_IMPORTED: 'Email imported',
  EMAIL_PARSED: 'Email parsed',
  LINKS_CLASSIFIED: 'Links classified',
  DOCUMENTS_PROCESSED: 'Reports downloaded',
  KNOWLEDGE_INDEXED: 'Knowledge base indexed',
  PORTFOLIO_MATCHED: 'Matched to holdings',
  IMPACT_ASSESSED: 'Impact assessed',
  COMPLETED: 'Finished',
};

const REASONS: Record<string, string> = {
  NOT_APPLICABLE: 'Not applicable to a library document.',
  NO_LINKS: 'The email has no links.',
  NO_ELIGIBLE_LINKS: 'No research report links to download.',
  AUTO_DOWNLOAD_DISABLED: 'Automatic download is off for this source; use the Download buttons on the links.',
  DOWNLOAD_LIMIT_REACHED: 'Some reports were not downloaded because of the per-email or per-sync limit.',
  SOME_DOWNLOADS_FAILED: 'Some reports could not be downloaded.',
  DOWNLOADS_FAILED: 'No report could be downloaded.',
  DOWNLOAD_TIMEOUT: 'Downloads did not finish in time.',
  EMBEDDINGS_NOT_CONFIGURED: 'No embedding model is configured, so nothing was indexed.',
  SOME_DOCUMENTS_NOT_INDEXED: 'Some reports could not be indexed.',
  INDEXING_FAILED: 'Indexing failed.',
  INDEX_TIMEOUT: 'Indexing did not finish in time.',
  NO_RELEVANT_HOLDINGS: 'No holding in your portfolios is named in this research.',
  AUTO_ASSESS_DISABLED: 'Automatic assessment is turned off.',
  DAILY_LIMIT_REACHED: 'The daily limit of automatic assessments was reached.',
  SOME_ASSESSMENTS_FAILED: 'Some assessments failed.',
  ASSESSMENTS_FAILED: 'The assessments failed.',
  NOT_REACHED: 'Not reached because an earlier stage failed.',
};

/**
 * Automated processing of one email or library document: stage list, matched holdings, and Process /
 * Retry actions. Polls while the workflow is running. Not a full Automation Center (Milestone 4).
 */
@Component({
  selector: 'app-workflow-status',
  imports: [DatePipe, StatusBadge],
  template: `
    <section class="card workflow-status">
      <div class="card__header">
        <h2 class="card__title">Automated processing</h2>
        @if (workflow(); as w) {
          <app-status-badge [value]="'WORKFLOW_' + w.status" />
        }
      </div>
      @if (error(); as message) {
        <p class="link-error">{{ message }}</p>
      }
      @if (workflow(); as w) {
        @if (w.status === 'NOT_STARTED') {
          <p class="muted small">
            @if (w.importedBeforeAutomation) {
              Imported before automated processing was available. Process it to download eligible reports, index it and
              match it to your holdings.
            } @else {
              Processing will start shortly.
            }
          </p>
        } @else {
          <ol class="workflow-stages">
            @for (stage of visibleStages(); track stage.stage) {
              <li>
                <span>{{ label(stage.stage) }}</span>
                <app-status-badge [value]="'WORKFLOW_' + stage.status" />
                @if (stage.reason && reason(stage.reason); as text) {
                  <span class="muted small">{{ text }}</span>
                }
              </li>
            }
          </ol>
          @if (holdings().length) {
            <p class="small">Relevant holdings: <strong>{{ holdings().join(', ') }}</strong></p>
          }
          @for (job of failedJobs(); track job.id) {
            <p class="link-error small">
              {{ job.type }} failed after {{ job.attempts }} attempt{{ job.attempts === 1 ? '' : 's' }}@if (job.errorSummary) {: {{ job.errorSummary }}}
            </p>
          }
          @if (w.completedAt) {
            <p class="muted small">Finished {{ w.completedAt | date: 'medium' }}</p>
          }
        }
        <div class="actions">
          @if (failedJobs().length) {
            <button type="button" class="btn btn--small btn--primary" [disabled]="busy()" (click)="process()">Retry failed steps</button>
          } @else if (w.status !== 'RUNNING' && w.status !== 'PENDING') {
            <button type="button" class="btn btn--small" [disabled]="busy()" (click)="process()">
              {{ w.status === 'NOT_STARTED' ? 'Process now' : 'Process again' }}
            </button>
          }
        </div>
      } @else if (!error()) {
        <p class="muted small">Loading processing status…</p>
      }
    </section>
  `,
})
export class WorkflowStatusPanel {
  private readonly api = inject(WorkflowApiService);

  readonly sourceId = input.required<string>();

  protected readonly workflow = signal<ResearchWorkflow | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly visibleStages = computed(
    () => this.workflow()?.stages.filter((s) => s.stage !== 'COMPLETED' && s.reason !== 'NOT_APPLICABLE') ?? [],
  );
  protected readonly failedJobs = computed(() => this.workflow()?.jobs.filter((j) => j.status === 'FAILED') ?? []);
  protected readonly holdings = computed(() => [
    ...new Set((this.workflow()?.matches ?? []).map((m) => m.symbol).filter((s): s is string => !!s)),
  ]);
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => this.load(this.sourceId()));
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  protected label(stage: WorkflowStageName): string {
    return STAGE_LABELS[stage] ?? stage;
  }

  protected reason(code: string): string | null {
    return REASONS[code] ?? null;
  }

  protected process(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.process(this.sourceId()).subscribe({
      next: (workflow) => {
        this.busy.set(false);
        this.show(workflow, true);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not start processing.'));
      },
    });
  }

  private load(id: string): void {
    this.stopPolling();
    this.api.workflow(id).subscribe({
      next: (workflow) => this.show(workflow, false),
      error: (err) => this.error.set(errorMessage(err, 'Could not load the processing status.')),
    });
  }

  private show(workflow: ResearchWorkflow, justRequested: boolean): void {
    this.workflow.set(workflow);
    this.stopPolling();
    const active = workflow.status === 'RUNNING' || workflow.status === 'PENDING';
    if (active || justRequested || (workflow.status === 'NOT_STARTED' && !workflow.importedBeforeAutomation)) {
      this.timer = setTimeout(() => this.load(this.sourceId()), POLL_MS);
    }
  }

  private stopPolling(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}

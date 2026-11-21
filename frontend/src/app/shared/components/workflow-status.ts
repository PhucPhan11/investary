import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';

import { WorkflowApiService } from '../../core/api/automation-api.service';
import { errorMessage } from '../../core/api/error-message';
import { ResearchWorkflow, WorkflowStageName } from '../../core/models/automation.model';
import { StatusBadge } from './status-badge';

const POLL_MS = 3000;

export const STAGE_LABELS: Record<WorkflowStageName, string> = {
  EMAIL_IMPORTED: $localize`:@@workflowStage.EMAIL_IMPORTED:Email imported`,
  EMAIL_PARSED: $localize`:@@workflowStage.EMAIL_PARSED:Email parsed`,
  LINKS_CLASSIFIED: $localize`:@@workflowStage.LINKS_CLASSIFIED:Links classified`,
  DOCUMENTS_PROCESSED: $localize`:@@workflowStage.DOCUMENTS_PROCESSED:Reports downloaded`,
  KNOWLEDGE_INDEXED: $localize`:@@workflowStage.KNOWLEDGE_INDEXED:Knowledge base indexed`,
  PORTFOLIO_MATCHED: $localize`:@@workflowStage.PORTFOLIO_MATCHED:Matched to holdings`,
  IMPACT_ASSESSED: $localize`:@@workflowStage.IMPACT_ASSESSED:Impact assessed`,
  COMPLETED: $localize`:@@workflowStage.COMPLETED:Finished`,
};

export const REASONS: Record<string, string> = {
  NOT_APPLICABLE: $localize`:@@workflowReason.NOT_APPLICABLE:Not applicable to a library document.`,
  NO_LINKS: $localize`:@@workflowReason.NO_LINKS:The email has no links.`,
  NO_ELIGIBLE_LINKS: $localize`:@@workflowReason.NO_ELIGIBLE_LINKS:No research report links to download.`,
  AUTO_DOWNLOAD_DISABLED: $localize`:@@workflowReason.AUTO_DOWNLOAD_DISABLED:Automatic download is off for this source; use the Download buttons on the links.`,
  DOWNLOAD_LIMIT_REACHED: $localize`:@@workflowReason.DOWNLOAD_LIMIT_REACHED:Some reports were not downloaded because of the per-email or per-sync limit.`,
  SOME_DOWNLOADS_FAILED: $localize`:@@workflowReason.SOME_DOWNLOADS_FAILED:Some reports could not be downloaded.`,
  DOWNLOADS_FAILED: $localize`:@@workflowReason.DOWNLOADS_FAILED:No report could be downloaded.`,
  DOWNLOAD_TIMEOUT: $localize`:@@workflowReason.DOWNLOAD_TIMEOUT:Downloads did not finish in time.`,
  EMBEDDINGS_NOT_CONFIGURED: $localize`:@@workflowReason.EMBEDDINGS_NOT_CONFIGURED:No embedding model is configured, so nothing was indexed.`,
  SOME_DOCUMENTS_NOT_INDEXED: $localize`:@@workflowReason.SOME_DOCUMENTS_NOT_INDEXED:Some reports could not be indexed.`,
  INDEXING_FAILED: $localize`:@@workflowReason.INDEXING_FAILED:Indexing failed.`,
  INDEX_TIMEOUT: $localize`:@@workflowReason.INDEX_TIMEOUT:Indexing did not finish in time.`,
  NO_RELEVANT_HOLDINGS: $localize`:@@workflowReason.NO_RELEVANT_HOLDINGS:No holding in your portfolios is named in this research.`,
  AUTO_ASSESS_DISABLED: $localize`:@@workflowReason.AUTO_ASSESS_DISABLED:Automatic assessment is turned off.`,
  DAILY_LIMIT_REACHED: $localize`:@@workflowReason.DAILY_LIMIT_REACHED:The daily limit of automatic assessments was reached.`,
  TOKEN_BUDGET_REACHED: $localize`:@@workflowReason.TOKEN_BUDGET_REACHED:The daily AI token budget for automatic calls was used up.`,
  SOME_ASSESSMENTS_FAILED: $localize`:@@workflowReason.SOME_ASSESSMENTS_FAILED:Some assessments failed.`,
  ASSESSMENTS_FAILED: $localize`:@@workflowReason.ASSESSMENTS_FAILED:The assessments failed.`,
  NOT_REACHED: $localize`:@@workflowReason.NOT_REACHED:Not reached because an earlier stage failed.`,
};

/**
 * Automated processing of one email or library document: stage list, matched holdings, and Process /
 * Retry actions. Polls while the workflow is running. The Automation Center shows all jobs.
 */
@Component({
  selector: 'app-workflow-status',
  imports: [DatePipe, StatusBadge],
  template: `
    <section class="card workflow-status">
      <div class="card__header">
        <h2 class="card__title" i18n="@@workflowStatus.title">Automated processing</h2>
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
              <ng-container i18n="@@workflowStatus.importedBefore">Imported before automated processing was available. Process it to download eligible reports, index it and
              match it to your holdings.</ng-container>
            } @else {
              <ng-container i18n="@@workflowStatus.startingSoon">Processing will start shortly.</ng-container>
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
            <p class="small" i18n="@@workflowStatus.holdings">Relevant holdings: <strong>{{ holdings().join(', ') }}</strong></p>
          }
          @for (job of failedJobs(); track job.id) {
            <p class="link-error small">
              <ng-container i18n="@@workflowStatus.jobFailed">{{ job.type }} failed after {job.attempts, plural, =1 {1 attempt} other {{{ job.attempts }} attempts}}</ng-container>@if (job.errorSummary) {: {{ job.errorSummary }}}
            </p>
          }
          @if (w.completedAt) {
            <p class="muted small" i18n="@@workflowStatus.finished">Finished {{ w.completedAt | date: 'medium' }}</p>
          }
        }
        <div class="actions">
          @if (failedJobs().length) {
            <button type="button" class="btn btn--small btn--primary" [disabled]="busy()" (click)="process()" i18n="@@workflowStatus.retryFailed">Retry failed steps</button>
          } @else if (w.status !== 'RUNNING' && w.status !== 'PENDING') {
            <button type="button" class="btn btn--small" [disabled]="busy()" (click)="process()">
              @if (w.status === 'NOT_STARTED') {
                <ng-container i18n="@@workflowStatus.processNow">Process now</ng-container>
              } @else {
                <ng-container i18n="@@workflowStatus.processAgain">Process again</ng-container>
              }
            </button>
          }
        </div>
      } @else if (!error()) {
        <p class="muted small" i18n="@@workflowStatus.loading">Loading processing status…</p>
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
        this.error.set(errorMessage(err, $localize`:@@workflowStatus.error.startProcessing:Could not start processing.`));
      },
    });
  }

  private load(id: string): void {
    this.stopPolling();
    this.api.workflow(id).subscribe({
      next: (workflow) => this.show(workflow, false),
      error: (err) => this.error.set(errorMessage(err, $localize`:@@workflowStatus.error.loadProcessingStatus:Could not load the processing status.`)),
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

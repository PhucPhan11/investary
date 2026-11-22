import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { DocumentApiService } from '../../core/api/document-api.service';
import { EmailApiService } from '../../core/api/email-api.service';
import { errorMessage } from '../../core/api/error-message';
import { KnowledgeApiService } from '../../core/api/knowledge-api.service';
import { NotebookApiService } from '../../core/api/notebook-api.service';
import { ResearchDocument } from '../../core/models/document.model';
import { EmailSummary } from '../../core/models/email.model';
import { EvidenceStatus, KnowledgeSourceType } from '../../core/models/knowledge.model';
import {
  FaqContent,
  Note,
  NoteKind,
  NotebookCitation,
  NotebookDetail as Notebook,
  NotebookMessage,
  NotebookSource,
  SourceIndexState,
  SummaryContent,
  ViewsContent,
} from '../../core/models/notebook.model';
import { Alert } from '../../shared/components/alert';
import { AnswerSegment, answerSegments } from '../assistant/answer-segments';

type Tab = 'chat' | 'studio';
type PickerTab = 'documents' | 'emails' | 'filter';

const POLL_MS = 3000;

const EVIDENCE: Record<EvidenceStatus, { label: string; tone: string }> = {
  SUPPORTED: { label: $localize`:@@evidence.SUPPORTED:Supported by sources`, tone: 'success' },
  PARTIAL: { label: $localize`:@@evidence.PARTIAL:Partially supported`, tone: 'warning' },
  INSUFFICIENT: { label: $localize`:@@evidence.INSUFFICIENT:Insufficient evidence`, tone: 'danger' },
};

const INDEX_STATE: Record<SourceIndexState, string> = {
  INDEXED: $localize`:@@notebook.index.INDEXED:Searchable`,
  NOT_TRACKED: $localize`:@@notebook.index.NOT_TRACKED:Not indexed`,
  PENDING: $localize`:@@notebook.index.PENDING:Waiting to be indexed`,
  PROCESSING: $localize`:@@notebook.index.PROCESSING:Indexing`,
  FAILED: $localize`:@@notebook.index.FAILED:Indexing failed`,
  SKIPPED: $localize`:@@notebook.index.SKIPPED:No text found`,
  OUTDATED: $localize`:@@notebook.index.OUTDATED:Needs reindexing`,
};

interface NoteCard {
  kind: NoteKind;
  title: string;
  description: string;
}

/**
 * One notebook: its sources, a chat that uses only those sources (with history), and generated notes. AI text is
 * plain text split into segments; citation markers become buttons, never HTML.
 */
@Component({
  selector: 'app-notebook-detail',
  imports: [DatePipe, NgTemplateOutlet, FormsModule, RouterLink, Alert],
  templateUrl: './notebook-detail.html',
})
export class NotebookDetailPage {
  private readonly api = inject(NotebookApiService);
  private readonly documentsApi = inject(DocumentApiService);
  private readonly emailsApi = inject(EmailApiService);
  private readonly knowledge = inject(KnowledgeApiService);
  private readonly router = inject(Router);

  readonly id = input.required<string>();

  protected readonly cards: NoteCard[] = [
    {
      kind: 'SUMMARY',
      title: $localize`:@@notebook.card.SUMMARY:Briefing summary`,
      description: $localize`:@@notebook.card.SUMMARY.text:The key points across all sources, each one cited.`,
    },
    {
      kind: 'CONSENSUS',
      title: $localize`:@@notebook.card.CONSENSUS:Consensus and disagreement`,
      description: $localize`:@@notebook.card.CONSENSUS.text:What each brokerage says about one ticker, and where they agree or differ.`,
    },
    {
      kind: 'TIMELINE',
      title: $localize`:@@notebook.card.TIMELINE:Timeline of views`,
      description: $localize`:@@notebook.card.TIMELINE.text:How the sources' views on one ticker changed over time.`,
    },
    {
      kind: 'FAQ',
      title: $localize`:@@notebook.card.FAQ:Suggested questions`,
      description: $localize`:@@notebook.card.FAQ.text:Questions these sources can answer, with cited answers.`,
    },
  ];

  protected readonly notebook = signal<Notebook | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly tab = signal<Tab>('chat');

  // Chat
  protected readonly messages = signal<NotebookMessage[]>([]);
  protected readonly asking = signal(false);
  protected readonly pendingQuestion = signal<string | null>(null);
  protected readonly chatError = signal<string | null>(null);
  protected readonly highlighted = signal<string | null>(null);
  protected question = '';

  // Notes
  protected readonly notes = signal<Note[]>([]);
  protected readonly notesError = signal<string | null>(null);
  protected readonly requesting = signal<NoteKind | null>(null);
  protected tickerFor: Record<'CONSENSUS' | 'TIMELINE', string> = { CONSENSUS: '', TIMELINE: '' };
  protected readonly activeNotes = computed(() => this.notes().some((n) => n.status === 'PENDING' || n.status === 'RUNNING'));

  // Editing
  protected readonly editing = signal(false);
  protected editTitle = '';
  protected editDescription = '';

  // Source picker
  protected readonly pickerOpen = signal(false);
  protected readonly pickerTab = signal<PickerTab>('documents');
  protected readonly pickerDocuments = signal<ResearchDocument[] | null>(null);
  protected readonly pickerEmails = signal<EmailSummary[] | null>(null);
  protected readonly pickerError = signal<string | null>(null);
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly adding = signal(false);
  protected emailSearch = '';
  protected filter: { ticker: string; brokerage: string; sourceType: '' | KnowledgeSourceType; from: string; to: string } = {
    ticker: '',
    brokerage: '',
    sourceType: '',
    from: '',
    to: '',
  };

  protected readonly present = computed(() => new Set(this.notebook()?.sources.map((s) => s.sourceId) ?? []));
  protected readonly unsearchable = computed(() => this.notebook()?.sources.filter((s) => s.indexState !== 'INDEXED').length ?? 0);

  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  private load(id: string): void {
    this.loadError.set(null);
    this.api.get(id).subscribe({
      next: (notebook) => this.notebook.set(notebook),
      error: (err) => this.loadError.set(errorMessage(err, $localize`:@@notebook.error.load:Could not load the notebook.`)),
    });
    this.api.messages(id).subscribe({
      next: (messages) => this.messages.set(messages),
      error: (err) => this.chatError.set(errorMessage(err, $localize`:@@notebook.error.messages:Could not load the conversation.`)),
    });
    this.loadNotes();
  }

  // ---- notebook ----

  protected startEdit(n: Notebook): void {
    this.editTitle = n.title;
    this.editDescription = n.description ?? '';
    this.editing.set(true);
  }

  protected saveEdit(n: Notebook): void {
    if (!this.editTitle.trim()) {
      return;
    }
    this.api.update(n.id, this.editTitle.trim(), this.editDescription.trim() || null, n.version).subscribe({
      next: (updated) => {
        this.notebook.set(updated);
        this.editing.set(false);
        this.actionError.set(null);
      },
      error: (err) => this.actionError.set(errorMessage(err, $localize`:@@notebook.error.save:Could not save the notebook.`)),
    });
  }

  protected remove(n: Notebook): void {
    if (!confirm($localize`:@@notebook.confirmDelete:Delete this notebook with its conversation and notes? Documents and emails are not deleted.`)) {
      return;
    }
    this.api.delete(n.id).subscribe({
      next: () => this.router.navigate(['/notebooks']),
      error: (err) => this.actionError.set(errorMessage(err, $localize`:@@notebook.error.delete:Could not delete the notebook.`)),
    });
  }

  // ---- sources ----

  protected openPicker(): void {
    this.pickerOpen.set(true);
    this.selected.set(new Set());
    this.pickerError.set(null);
    if (this.pickerDocuments() === null) {
      this.documentsApi.list(0, 50).subscribe({
        next: (page) => this.pickerDocuments.set(page.content),
        error: (err) => this.pickerError.set(errorMessage(err, $localize`:@@notebook.error.documents:Could not load documents.`)),
      });
    }
    if (this.pickerEmails() === null) {
      this.searchEmails();
    }
  }

  protected searchEmails(): void {
    this.emailsApi.list({ q: this.emailSearch, size: 50 }).subscribe({
      next: (page) => this.pickerEmails.set(page.content),
      error: (err) => this.pickerError.set(errorMessage(err, $localize`:@@notebook.error.emails:Could not load emails.`)),
    });
  }

  protected toggle(id: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected addSelected(n: Notebook): void {
    const selected = this.selected();
    const documentIds = (this.pickerDocuments() ?? []).filter((d) => selected.has(d.id)).map((d) => d.id);
    const messageIds = (this.pickerEmails() ?? []).filter((e) => selected.has(e.id)).map((e) => e.id);
    if (!documentIds.length && !messageIds.length) {
      return;
    }
    this.add(n, { documentIds, messageIds });
  }

  protected addByFilter(n: Notebook): void {
    const f = this.filter;
    if (!f.ticker && !f.brokerage && !f.sourceType && !f.from && !f.to) {
      return;
    }
    this.add(n, { filter: { ticker: f.ticker, brokerage: f.brokerage, sourceType: f.sourceType || null, from: f.from, to: f.to } });
  }

  private add(n: Notebook, request: Parameters<NotebookApiService['addSources']>[1]): void {
    this.adding.set(true);
    this.pickerError.set(null);
    this.api.addSources(n.id, request).subscribe({
      next: (updated) => {
        this.notebook.set(updated);
        this.adding.set(false);
        this.pickerOpen.set(false);
      },
      error: (err) => {
        this.adding.set(false);
        this.pickerError.set(errorMessage(err, $localize`:@@notebook.error.addSources:Could not add the sources.`));
      },
    });
  }

  protected removeSource(n: Notebook, source: NotebookSource): void {
    this.api.removeSource(n.id, source.sourceId).subscribe({
      next: (updated) => this.notebook.set(updated),
      error: (err) => this.actionError.set(errorMessage(err, $localize`:@@notebook.error.removeSource:Could not remove the source.`)),
    });
  }

  protected index(n: Notebook, source: NotebookSource): void {
    this.knowledge.index(source.sourceType, source.sourceId).subscribe({
      next: () => this.load(n.id),
      error: (err) => this.actionError.set(errorMessage(err, $localize`:@@notebook.error.index:Could not queue indexing.`)),
    });
  }

  protected indexState(state: SourceIndexState): string {
    return INDEX_STATE[state];
  }

  // ---- chat ----

  protected ask(text = this.question): void {
    const question = text.trim();
    const n = this.notebook();
    if (!n || question.length < 3 || this.asking()) {
      return;
    }
    this.tab.set('chat');
    this.asking.set(true);
    this.pendingQuestion.set(question);
    this.chatError.set(null);
    this.question = '';
    this.api.ask(n.id, question).subscribe({
      next: (result) => {
        this.messages.update((list) => [...list, result.question, result.answer]);
        this.asking.set(false);
        this.pendingQuestion.set(null);
      },
      error: (err) => {
        this.asking.set(false);
        this.pendingQuestion.set(null);
        this.question = question;
        this.chatError.set(errorMessage(err, $localize`:@@notebook.error.ask:The assistant could not answer.`));
      },
    });
  }

  protected clearChat(n: Notebook): void {
    if (!confirm($localize`:@@notebook.confirmClear:Clear the whole conversation? Notes are kept.`)) {
      return;
    }
    this.api.clearMessages(n.id).subscribe({
      next: () => this.messages.set([]),
      error: (err) => this.chatError.set(errorMessage(err, $localize`:@@notebook.error.clear:Could not clear the conversation.`)),
    });
  }

  // ---- notes ----

  protected generate(n: Notebook, kind: NoteKind): void {
    const ticker = kind === 'CONSENSUS' || kind === 'TIMELINE' ? this.tickerFor[kind] : null;
    if ((kind === 'CONSENSUS' || kind === 'TIMELINE') && !ticker) {
      return;
    }
    this.requesting.set(kind);
    this.notesError.set(null);
    this.api.requestNote(n.id, kind, ticker).subscribe({
      next: (note) => {
        this.requesting.set(null);
        this.notes.update((list) => [note, ...list.filter((other) => other.id !== note.id)]);
        this.schedulePoll();
      },
      error: (err) => {
        this.requesting.set(null);
        this.notesError.set(errorMessage(err, $localize`:@@notebook.error.generate:Could not start the note.`));
      },
    });
  }

  protected retry(n: Notebook, note: Note): void {
    this.api.retryNote(n.id, note.id).subscribe({
      next: (updated) => {
        this.notes.update((list) => list.map((other) => (other.id === updated.id ? updated : other)));
        this.schedulePoll();
      },
      error: (err) => this.notesError.set(errorMessage(err, $localize`:@@notebook.error.retry:Could not retry the note.`)),
    });
  }

  private loadNotes(): void {
    this.api.notes(this.id()).subscribe({
      next: (notes) => {
        this.notes.set(notes);
        this.schedulePoll();
      },
      error: (err) => this.notesError.set(errorMessage(err, $localize`:@@notebook.error.notes:Could not load notes.`)),
    });
  }

  private schedulePoll(): void {
    this.stopPolling();
    if (this.activeNotes()) {
      this.pollTimer = setTimeout(() => this.loadNotes(), POLL_MS);
    }
  }

  private stopPolling(): void {
    if (this.pollTimer !== null) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  protected cardTitle(kind: NoteKind): string {
    return this.cards.find((c) => c.kind === kind)?.title ?? kind;
  }

  protected summary(note: Note): SummaryContent {
    return note.content as SummaryContent;
  }

  protected faq(note: Note): FaqContent {
    return note.content as FaqContent;
  }

  protected views(note: Note): ViewsContent {
    return note.content as ViewsContent;
  }

  // ---- shared ----

  protected segments(text: string): AnswerSegment[] {
    return answerSegments(text);
  }

  protected evidence(status: EvidenceStatus) {
    return EVIDENCE[status];
  }

  protected anchor(ownerId: string, label: string): string {
    return `cite-${ownerId}-${label}`;
  }

  protected showSource(ownerId: string, label: string): void {
    const key = this.anchor(ownerId, label);
    this.highlighted.set(key);
    document.getElementById(key)?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
  }

  protected showSourceLabel(label: string): string {
    return $localize`:@@researchAssistant.showSource:Show source ${label}:label:`;
  }

  protected sourceLink(type: KnowledgeSourceType, id: string): unknown[] {
    return [type === 'DOCUMENT' ? '/documents' : '/emails', id];
  }

  protected citationLink(c: NotebookCitation): unknown[] | null {
    return c.available && c.sourceId ? this.sourceLink(c.sourceType, c.sourceId) : null;
  }
}

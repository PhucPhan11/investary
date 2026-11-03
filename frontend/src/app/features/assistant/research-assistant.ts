import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/error-message';
import { IntelligenceApiService } from '../../core/api/intelligence-api.service';
import { KnowledgeApiService, ResearchApiService } from '../../core/api/knowledge-api.service';
import { PortfolioApiService } from '../../core/api/portfolio-api.service';
import {
  AssistantAnswer,
  Citation,
  EvidenceStatus,
  KnowledgeSourceType,
  KnowledgeStatus,
  ResearchFilters,
} from '../../core/models/knowledge.model';
import { Portfolio } from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';
import { AnswerSegment, answerSegments } from './answer-segments';

export type AssistantMode = 'GENERAL' | 'PORTFOLIO';

/** Portfolio context of a portfolio-aware answer, shown apart from the cited AI text. */
interface PortfolioContext {
  name: string;
  facts: string;
  focusSymbols: string[];
}

interface Turn {
  id: number;
  question: string;
  mode: AssistantMode;
  portfolio?: PortfolioContext;
  filters: ResearchFilters;
  state: 'loading' | 'done' | 'error';
  answer?: AssistantAnswer;
  segments?: AnswerSegment[];
  error?: string;
}

const EVIDENCE: Record<EvidenceStatus, { label: string; tone: string }> = {
  SUPPORTED: { label: 'Supported by sources', tone: 'success' },
  PARTIAL: { label: 'Partially supported', tone: 'warning' },
  INSUFFICIENT: { label: 'Insufficient evidence', tone: 'danger' },
};

/**
 * Ask questions about stored research. Every question is answered on its own; the backend keeps no
 * conversation memory, so earlier turns are shown for reference only.
 */
@Component({
  selector: 'app-research-assistant',
  imports: [DatePipe, FormsModule, RouterLink, Alert],
  templateUrl: './research-assistant.html',
})
export class ResearchAssistant {
  private readonly research = inject(ResearchApiService);
  private readonly knowledge = inject(KnowledgeApiService);
  private readonly intelligence = inject(IntelligenceApiService);
  private readonly portfolioApi = inject(PortfolioApiService);

  protected readonly brokerages = ['TCBS', 'SSI', 'VNDIRECT', 'VCBS'];
  protected readonly examples = [
    'TCBS đang khuyến nghị gì về cổ phiếu SCS?',
    'Những rủi ro chính của SCS theo báo cáo gần nhất là gì?',
    'Báo cáo nào đề cập đến tác động của sân bay Long Thành?',
  ];

  protected question = '';
  protected filters: { ticker: string; brokerage: string; sourceType: '' | KnowledgeSourceType; from: string; to: string } = {
    ticker: '',
    brokerage: '',
    sourceType: '',
    from: '',
    to: '',
  };

  protected readonly mode = signal<AssistantMode>('GENERAL');
  protected readonly portfolios = signal<Portfolio[]>([]);
  protected readonly portfoliosError = signal<string | null>(null);
  /** Pre-selected only when exactly one portfolio exists; with several the user must choose. */
  protected portfolioId = '';
  protected readonly status = signal<KnowledgeStatus | null>(null);
  protected readonly statusError = signal<string | null>(null);
  protected readonly turns = signal<Turn[]>([]);
  protected readonly selectedTurnId = signal<number | null>(null);
  protected readonly highlighted = signal<string | null>(null);
  protected readonly asking = computed(() => this.turns().some((turn) => turn.state === 'loading'));
  protected readonly selectedTurn = computed(() => {
    const turns = this.turns();
    const id = this.selectedTurnId();
    return turns.find((turn) => turn.id === id) ?? [...turns].reverse().find((turn) => turn.state === 'done') ?? null;
  });
  private nextId = 1;

  constructor() {
    this.knowledge.status().subscribe({
      next: (status) => this.status.set(status),
      error: (err) => this.statusError.set(errorMessage(err, 'Could not load the knowledge base status.')),
    });
    this.portfolioApi.list().subscribe({
      next: (list) => {
        this.portfolios.set(list);
        if (list.length === 1) {
          this.portfolioId = list[0].id;
        }
      },
      error: (err) => this.portfoliosError.set(errorMessage(err, 'Could not load portfolios.')),
    });
  }

  /** True when a question can be sent in the current mode (portfolio mode needs an explicit portfolio). */
  protected canAsk(text = this.question): boolean {
    return text.trim().length >= 3 && !this.asking() && (this.mode() === 'GENERAL' || !!this.portfolioId);
  }

  protected ask(text = this.question): void {
    const question = text.trim();
    if (!this.canAsk(question)) {
      return;
    }
    if (this.mode() === 'PORTFOLIO') {
      this.askPortfolio(question, this.portfolioId);
      return;
    }
    const filters: ResearchFilters = {
      ticker: this.filters.ticker || null,
      brokerage: this.filters.brokerage || null,
      sourceType: this.filters.sourceType || null,
      from: this.filters.from || null,
      to: this.filters.to || null,
    };
    const turn: Turn = { id: this.nextId++, question, mode: 'GENERAL', filters, state: 'loading' };
    this.turns.update((turns) => [...turns, turn]);
    this.question = '';
    this.research.ask(question, filters).subscribe({
      next: (answer) => {
        this.update(turn.id, { state: 'done', answer, segments: answerSegments(answer.answer) });
        this.selectedTurnId.set(turn.id);
        this.highlighted.set(null);
      },
      error: (err) => this.update(turn.id, { state: 'error', error: errorMessage(err, 'The assistant could not answer.') }),
    });
  }

  private askPortfolio(question: string, portfolioId: string): void {
    const name = this.portfolios().find((p) => p.id === portfolioId)?.name ?? '';
    const turn: Turn = { id: this.nextId++, question, mode: 'PORTFOLIO', filters: {}, state: 'loading' };
    this.turns.update((turns) => [...turns, turn]);
    this.question = '';
    this.intelligence.ask(question, portfolioId).subscribe({
      next: (answer) => {
        const asAssistant: AssistantAnswer = {
          answer: answer.answer,
          evidenceStatus: answer.evidenceStatus,
          citations: answer.citations,
          retrievedSourceIds: answer.retrievedSourceIds,
          coverageGaps: [],
          warnings: answer.warnings,
          newestEvidenceDate: answer.newestEvidenceDate,
          model: answer.model,
          disclaimer: answer.disclaimer,
        };
        this.update(turn.id, {
          state: 'done',
          answer: asAssistant,
          segments: answerSegments(answer.answer),
          portfolio: { name: answer.portfolioName || name, facts: answer.portfolioFacts, focusSymbols: answer.focusSymbols },
        });
        this.selectedTurnId.set(turn.id);
        this.highlighted.set(null);
      },
      error: (err) => this.update(turn.id, { state: 'error', error: errorMessage(err, 'The assistant could not answer.') }),
    });
  }

  protected showSource(turn: Turn, label: string): void {
    this.selectedTurnId.set(turn.id);
    this.highlighted.set(label);
    document.getElementById(this.citationAnchor(turn.id, label))?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
  }

  protected evidence(status: EvidenceStatus) {
    return EVIDENCE[status];
  }

  protected sourceLink(citation: Citation): unknown[] {
    return [citation.sourceType === 'DOCUMENT' ? '/documents' : '/emails', citation.sourceId];
  }

  protected citationAnchor(turnId: number, label: string): string {
    return `source-${turnId}-${label}`;
  }

  protected hasFilters(filters: ResearchFilters): boolean {
    return Object.values(filters).some((value) => !!value);
  }

  protected describeFilters(filters: ResearchFilters): string {
    return [
      filters.ticker && `ticker ${filters.ticker}`,
      filters.brokerage,
      filters.sourceType === 'DOCUMENT' ? 'PDF reports' : filters.sourceType === 'EMAIL' ? 'emails' : null,
      filters.from && `from ${filters.from}`,
      filters.to && `to ${filters.to}`,
    ]
      .filter(Boolean)
      .join(' · ');
  }

  protected clearFilters(): void {
    this.filters = { ticker: '', brokerage: '', sourceType: '', from: '', to: '' };
  }

  private update(id: number, changes: Partial<Turn>): void {
    this.turns.update((turns) => turns.map((turn) => (turn.id === id ? { ...turn, ...changes } : turn)));
  }
}

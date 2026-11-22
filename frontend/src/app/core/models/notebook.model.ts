import { EvidenceStatus, KnowledgeSourceType } from './knowledge.model';

export type NoteKind = 'SUMMARY' | 'CONSENSUS' | 'TIMELINE' | 'FAQ';
export type NoteStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
/** INDEXED sources are used; the others are listed but not searchable yet. */
export type SourceIndexState = 'INDEXED' | 'NOT_TRACKED' | 'PENDING' | 'PROCESSING' | 'FAILED' | 'SKIPPED' | 'OUTDATED';

export interface NotebookSummary {
  id: string;
  title: string;
  description: string | null;
  version: number;
  sourceCount: number;
  questionCount: number;
  noteCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface NotebookSource {
  sourceType: KnowledgeSourceType;
  sourceId: string;
  title: string | null;
  brokerage: string | null;
  publishedOn: string | null;
  dateBasis: string | null;
  sentAt: string | null;
  importedAt: string | null;
  tickers: string[];
  indexState: SourceIndexState;
  addedAt: string;
}

export interface NotebookDetail {
  id: string;
  title: string;
  description: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  sources: NotebookSource[];
  tickers: string[];
  searchableSources: number;
}

/** A citation copied when the answer or note was written; `available` is false once the source was deleted. */
export interface NotebookCitation {
  label: string;
  sourceType: KnowledgeSourceType;
  sourceId: string | null;
  available: boolean;
  chunkId: string | null;
  title: string;
  publisher: string | null;
  publishedOn: string | null;
  dateBasis: string | null;
  location: string | null;
  excerpt: string;
}

export interface NotebookMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  evidenceStatus: EvidenceStatus | null;
  warnings: string[];
  model: string | null;
  createdAt: string;
  citations: NotebookCitation[];
}

export interface AskResult {
  question: NotebookMessage;
  answer: NotebookMessage;
  coverageGaps: string[];
  disclaimer: string;
}

export interface NotePoint {
  text: string;
  evidenceStatus: EvidenceStatus;
  labels: string[];
}

export interface SummaryContent {
  points: NotePoint[];
}

export interface FaqContent {
  items: { question: string; answer: string; evidenceStatus: EvidenceStatus; labels: string[] }[];
}

/** One source in a consensus or timeline note; everything but `view` is copied from stored records. */
export interface NoteSourceEntry {
  group: string;
  sourceType: KnowledgeSourceType;
  sourceId: string;
  title: string | null;
  brokerage: string | null;
  date: string | null;
  dateBasis: 'PUBLISHED' | 'EMAIL_SENT' | 'IMPORTED';
  /** The brokerage's own rating, extracted from the email; never an Investary recommendation. */
  rating: string | null;
  ratingLocation: string | null;
  view: NotePoint | null;
}

export interface ViewsContent {
  ticker: string;
  sources: NoteSourceEntry[];
  agreements: NotePoint[];
  disagreements: NotePoint[];
}

export interface Note {
  id: string;
  kind: NoteKind;
  ticker: string | null;
  status: NoteStatus;
  content: SummaryContent | FaqContent | ViewsContent | null;
  limitations: string[];
  evidenceStatus: EvidenceStatus | null;
  model: string | null;
  errorSummary: string | null;
  createdAt: string;
  completedAt: string | null;
  citations: NotebookCitation[];
  reused: boolean;
}

export interface SourceFilter {
  ticker?: string | null;
  brokerage?: string | null;
  sourceType?: KnowledgeSourceType | null;
  from?: string | null;
  to?: string | null;
}

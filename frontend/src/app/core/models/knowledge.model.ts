export type KnowledgeSourceType = 'DOCUMENT' | 'EMAIL';

export type IndexStatus = 'NOT_INDEXED' | 'PENDING' | 'PROCESSING' | 'INDEXED' | 'FAILED' | 'SKIPPED';

/** Index state of one document or email (also its indexing job). */
export interface KnowledgeJob {
  id: string | null;
  sourceType: KnowledgeSourceType;
  sourceId: string;
  status: IndexStatus;
  /** e.g. OCR_REQUIRED, NO_TEXT, PARSING_FAILED */
  statusReason: string | null;
  indexVersion: string | null;
  /** False when the chunks were built with an older configuration; they are not searched until reindexed. */
  currentVersion: boolean;
  chunkCount: number;
  pageCount: number | null;
  attempts: number;
  maxAttempts: number;
  lastError: string | null;
  requestedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  nextAttemptAt: string | null;
}

export interface ModelStatus {
  available: boolean;
  provider: string | null;
  model: string | null;
  dimensions: number | null;
}

export interface KnowledgeStatus {
  embedding: ModelStatus;
  chat: ModelStatus;
  indexVersion: string;
  autoIndex: boolean;
  workerEnabled: boolean;
  sources: Record<Exclude<IndexStatus, 'NOT_INDEXED'>, number>;
  staleSources: number;
  untrackedSources: number;
  searchableSources: number;
  searchableChunks: number;
}

export type ReindexScope = 'ALL' | 'FAILED' | 'STALE' | 'MISSING';

export interface ResearchFilters {
  ticker?: string | null;
  brokerage?: string | null;
  sourceType?: KnowledgeSourceType | null;
  from?: string | null;
  to?: string | null;
}

export type EvidenceStatus = 'SUPPORTED' | 'PARTIAL' | 'INSUFFICIENT';

/** A source reference built by the backend from stored records. */
export interface Citation {
  /** Marker used in the answer text, e.g. "S2" for "[S2]". */
  label: string;
  sourceType: KnowledgeSourceType;
  sourceId: string;
  title: string;
  publisher: string | null;
  publishedAt: string | null;
  /** PUBLISHED or EMAIL_SENT */
  dateBasis: string | null;
  pageStart: number | null;
  pageEnd: number | null;
  section: string | null;
  location: string | null;
  senderName: string | null;
  senderAddress: string | null;
  sentAt: string | null;
  chunkId: string;
  excerpt: string;
}

export interface AssistantAnswer {
  answer: string;
  evidenceStatus: EvidenceStatus;
  citations: Citation[];
  retrievedSourceIds: string[];
  coverageGaps: string[];
  warnings: string[];
  newestEvidenceDate: string | null;
  model: string | null;
  disclaimer: string;
}

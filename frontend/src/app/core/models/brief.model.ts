import { EvidenceStatus, KnowledgeSourceType } from './knowledge.model';

/** Daily investment brief (Phase 5 / Milestone 3). */
export type BriefTrigger = 'SCHEDULED' | 'MANUAL' | 'REGENERATION';
export type BriefStatus = 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'SKIPPED';
export type ResearchState = 'NEW_RESEARCH' | 'NO_MATERIAL_RESEARCH' | 'PENDING_ONLY';
export type BriefAiStatus = 'COMPLETED' | 'NOT_CONFIGURED' | 'FAILED' | 'SKIPPED_NO_EVIDENCE' | 'SKIPPED_LIMIT';
export type BriefJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type BriefJobOutcome = 'CREATED' | 'UNCHANGED' | 'SKIPPED';
/** PORTFOLIO_FACT: computed by Investary; SOURCE_CLAIM: a brokerage statement; AI_INTERPRETATION: validated model text. */
export type BriefItemKind = 'PORTFOLIO_FACT' | 'SOURCE_CLAIM' | 'AI_INTERPRETATION' | 'SYSTEM_NOTE';
export type BriefSourceRole = 'CITED' | 'INCLUDED' | 'PENDING' | 'OMITTED';
export type Relevance = 'DIRECT' | 'MENTION' | 'MARKET' | 'OTHER';
export type BriefSectionKey =
  | 'EXECUTIVE_SUMMARY'
  | 'MARKET_OVERVIEW'
  | 'PORTFOLIO_UPDATES'
  | 'OPPORTUNITIES'
  | 'RISKS_AND_CONFLICTS'
  | 'FOLLOW_UPS'
  | 'SOURCES_AND_LIMITATIONS';

export interface BriefItem {
  kind: BriefItemKind;
  /** Plain text; "[S1]" markers refer to the brief's citations. Never render as HTML. */
  text: string;
  evidenceStatus: EvidenceStatus | null;
  citations: string[];
  symbol: string | null;
  relevance: Relevance | null;
  publishedOn: string | null;
  dateBasis: string | null;
  brokerage: string | null;
  assessmentId: string | null;
}

export interface BriefSection {
  key: BriefSectionKey;
  title: string;
  note: string | null;
  evidenceStatus: EvidenceStatus | null;
  items: BriefItem[];
}

export interface BriefCitation {
  label: string;
  role: BriefSourceRole;
  sourceType: KnowledgeSourceType;
  /** Null when the source was deleted after the brief was written. */
  sourceId: string | null;
  title: string | null;
  brokerage: string | null;
  publishedOn: string | null;
  dateBasis: string | null;
  sentAt: string | null;
  importedAt: string | null;
  indexedAt: string | null;
  pageStart: number | null;
  pageEnd: number | null;
  section: string | null;
  location: string | null;
  excerpt: string | null;
  symbols: string[];
  relevance: Relevance | null;
  oldPublication: boolean;
  lateArrival: boolean;
  receivedVia: string[];
}

export interface BriefSourceRef {
  role: BriefSourceRole;
  sourceType: KnowledgeSourceType;
  sourceId: string | null;
  title: string | null;
  brokerage: string | null;
  publishedOn: string | null;
  dateBasis: string | null;
  sentAt: string | null;
  importedAt: string | null;
  indexedAt: string | null;
  state: string | null;
}

export interface Limitation {
  code: string;
  message: string;
}

export interface RevisionRef {
  id: string;
  revision: number;
  triggerType: BriefTrigger;
  status: BriefStatus;
  generatedAt: string;
}

export interface BriefListItem {
  id: string;
  portfolioId: string | null;
  portfolioName: string | null;
  triggerType: BriefTrigger;
  status: BriefStatus;
  researchState: ResearchState | null;
  periodStart: string;
  periodEnd: string;
  zone: string;
  businessDate: string;
  partialPeriod: boolean;
  generatedAt: string;
  revision: number;
  aiStatus: BriefAiStatus;
  evidenceStatus: EvidenceStatus | null;
  newSources: number;
  pendingSources: number;
  errorCode: string | null;
}

export interface Brief {
  id: string;
  portfolioId: string | null;
  portfolioName: string | null;
  briefType: string;
  triggerType: BriefTrigger;
  status: BriefStatus;
  researchState: ResearchState | null;
  periodStart: string;
  periodEnd: string;
  zone: string;
  businessDate: string;
  partialPeriod: boolean;
  generatedAt: string;
  sourceCutoff: string;
  language: string;
  sections: BriefSection[];
  citations: BriefCitation[];
  pendingSources: BriefSourceRef[];
  omittedSources: BriefSourceRef[];
  limitations: Limitation[];
  aiStatus: BriefAiStatus;
  model: string | null;
  promptVersion: string;
  configVersion: string;
  evidenceStatus: EvidenceStatus | null;
  errorCode: string | null;
  errorSummary: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  revision: number;
  rootBriefId: string;
  previousRevisionId: string | null;
  revisions: RevisionRef[];
  disclaimer: string;
}

export interface BriefJob {
  id: string;
  triggerType: BriefTrigger;
  status: BriefJobStatus;
  outcome: BriefJobOutcome | null;
  portfolioId: string | null;
  periodStart: string;
  periodEnd: string;
  zone: string;
  businessDate: string;
  partialPeriod: boolean;
  previousBriefId: string | null;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string;
  briefId: string | null;
  errorCode: string | null;
  errorSummary: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface BriefFilters {
  portfolioId?: string;
  from?: string;
  to?: string;
  status?: BriefStatus | '';
}

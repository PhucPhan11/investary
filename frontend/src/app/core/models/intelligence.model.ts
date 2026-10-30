import { Citation, EvidenceStatus, KnowledgeSourceType } from './knowledge.model';
import { Holding, InvestorProfile, PortfolioSummary } from './portfolio.model';

export type MatchKind = 'DIRECT' | 'MENTION' | 'MARKET_CONTEXT';
export type ImpactCategory =
  | 'DIRECT_HOLDING'
  | 'MARKET_CONTEXT'
  | 'POTENTIAL_RISK'
  | 'POTENTIAL_OPPORTUNITY'
  | 'MONITORING_ONLY'
  | 'INSUFFICIENT_EVIDENCE';
export type AiStatus = 'NOT_CONFIGURED' | 'COMPLETED' | 'SKIPPED_NO_EVIDENCE';
export type StaleReason = 'HOLDINGS_CHANGED' | 'EVIDENCE_CHANGED';

/** A stored chunk referenced as evidence (never model text). */
export interface EvidenceReference {
  label: string | null;
  matchKind: MatchKind;
  sourceType: KnowledgeSourceType;
  sourceId: string;
  title: string;
  publisher: string | null;
  publishedAt: string | null;
  dateBasis: string | null;
  location: string | null;
  excerpt: string;
  chunkId: string;
  contentSha256: string | null;
  outdated: boolean;
}

export interface HoldingResearch {
  instrumentId: string;
  symbol: string;
  name: string;
  direct: EvidenceReference[];
  mentions: EvidenceReference[];
  newestEvidenceDate: string | null;
}

export interface RelevantResearch {
  portfolioId: string;
  today: string;
  freshDays: number;
  searchMode: 'HYBRID' | 'LEXICAL_ONLY';
  holdings: HoldingResearch[];
  marketContext: EvidenceReference[];
  disclaimer: string;
}

export interface Observation {
  code: string;
  severity: 'INFO' | 'ATTENTION';
  symbol: string | null;
  message: string;
}

export interface ResearchCoverage {
  instrumentId: string;
  symbol: string;
  directCount: number;
  mentionCount: number;
  newestEvidenceDate: string | null;
  outdated: boolean;
}

export interface PortfolioAnalysis {
  portfolioId: string;
  summary: PortfolioSummary;
  holdings: Holding[];
  profile: InvestorProfile;
  goalCount: number;
  observations: Observation[];
  researchCoverage: ResearchCoverage[];
  searchMode: 'HYBRID' | 'LEXICAL_ONLY';
  disclaimer: string;
}

export interface AssessmentItem {
  instrumentId: string;
  symbol: string;
  deterministicCategory: ImpactCategory;
  category: ImpactCategory;
  summary: string | null;
  /** True when category and summary are a validated AI interpretation. */
  aiInterpretation: boolean;
  evidenceStatus: EvidenceStatus;
  evidence: EvidenceReference[];
}

export interface Assessment {
  id: string;
  portfolioId: string;
  createdAt: string;
  aiStatus: AiStatus;
  model: string | null;
  overallSummary: string | null;
  overallEvidenceStatus: EvidenceStatus | null;
  warnings: string[];
  stale: boolean;
  staleReasons: StaleReason[];
  snapshot: { portfolioName: string; currency: string; valuationDate: string; totalCostBasis: string };
  items: AssessmentItem[];
  disclaimer: string;
}

export interface AssessmentListItem {
  id: string;
  createdAt: string;
  aiStatus: AiStatus;
  itemCount: number;
  stale: boolean;
  staleReasons: StaleReason[];
}

export interface PortfolioAnswer {
  portfolioId: string;
  portfolioName: string;
  portfolioSummary: PortfolioSummary;
  holdings: Holding[];
  portfolioFacts: string;
  profileConfigured: boolean;
  goalCount: number;
  focusSymbols: string[];
  answer: string;
  evidenceStatus: EvidenceStatus;
  citations: Citation[];
  retrievedSourceIds: string[];
  warnings: string[];
  newestEvidenceDate: string | null;
  model: string | null;
  disclaimer: string;
}

export interface IntelligenceFilters {
  brokerage?: string | null;
  from?: string | null;
  to?: string | null;
}

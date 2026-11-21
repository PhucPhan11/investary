/** Investment decision journal (Phase 6 / Milestone 1). Amounts arrive as JSON numbers. */

import { Transaction } from './portfolio.model';

export type DecisionType = 'BUY' | 'SELL' | 'HOLD' | 'REBALANCE' | 'OBSERVE';
/** DRAFT → DECIDED → EXECUTED, or DRAFT | DECIDED → CANCELLED. */
export type DecisionStatus = 'DRAFT' | 'DECIDED' | 'EXECUTED' | 'CANCELLED';
export type EvidenceKind = 'DOCUMENT' | 'EMAIL' | 'RESEARCH_ITEM' | 'CITATION' | 'IMPACT_ASSESSMENT';
export type EvidenceRole = 'SUPPORTING' | 'CONTRADICTING' | 'BACKGROUND';
export type DecisionAction = 'EDIT' | 'AMEND' | 'FINALIZE' | 'EXECUTE' | 'CANCEL' | 'ATTACH_EVIDENCE' | 'LINK_TRANSACTION';
export type LinkState = 'CURRENT' | 'CHANGED' | 'DELETED';
export type RevisionKind =
  | 'CREATED'
  | 'EDITED'
  | 'FINALIZED'
  | 'AMENDED'
  | 'EXECUTED'
  | 'CANCELLED'
  | 'EVIDENCE_ATTACHED'
  | 'EVIDENCE_REMOVED'
  | 'TRANSACTION_LINKED'
  | 'TRANSACTION_UNLINKED';

export const DECISION_TYPES: DecisionType[] = ['BUY', 'SELL', 'HOLD', 'REBALANCE', 'OBSERVE'];
export const DECISION_STATUSES: DecisionStatus[] = ['DRAFT', 'DECIDED', 'EXECUTED', 'CANCELLED'];
export const EVIDENCE_KINDS: EvidenceKind[] = ['DOCUMENT', 'EMAIL', 'RESEARCH_ITEM', 'CITATION', 'IMPACT_ASSESSMENT'];
export const EVIDENCE_ROLES: EvidenceRole[] = ['SUPPORTING', 'CONTRADICTING', 'BACKGROUND'];

export const DECISION_TYPE_LABELS: Record<DecisionType, string> = {
  BUY: $localize`:@@journal.decisionTypeLabels.BUY:Buy`,
  SELL: $localize`:@@journal.decisionTypeLabels.SELL:Sell`,
  HOLD: $localize`:@@journal.decisionTypeLabels.HOLD:Hold`,
  REBALANCE: $localize`:@@journal.decisionTypeLabels.REBALANCE:Rebalance`,
  OBSERVE: $localize`:@@journal.decisionTypeLabels.OBSERVE:Observe`,
};

export const EVIDENCE_KIND_LABELS: Record<EvidenceKind, string> = {
  DOCUMENT: $localize`:@@journal.evidenceKindLabels.DOCUMENT:PDF document`,
  EMAIL: $localize`:@@journal.evidenceKindLabels.EMAIL:Email`,
  RESEARCH_ITEM: $localize`:@@journal.evidenceKindLabels.RESEARCH_ITEM:Research item`,
  CITATION: $localize`:@@journal.evidenceKindLabels.CITATION:Citation (knowledge chunk)`,
  IMPACT_ASSESSMENT: $localize`:@@journal.evidenceKindLabels.IMPACT_ASSESSMENT:Impact assessment`,
};

export const EVIDENCE_ROLE_LABELS: Record<EvidenceRole, string> = {
  SUPPORTING: $localize`:@@journal.evidenceRoleLabels.SUPPORTING:Supporting`,
  CONTRADICTING: $localize`:@@journal.evidenceRoleLabels.CONTRADICTING:Contradicting`,
  BACKGROUND: $localize`:@@journal.evidenceRoleLabels.BACKGROUND:Background`,
};

export const REVISION_LABELS: Record<RevisionKind, string> = {
  CREATED: $localize`:@@journal.revisionLabels.CREATED:Created`,
  EDITED: $localize`:@@journal.revisionLabels.EDITED:Draft edited`,
  FINALIZED: $localize`:@@journal.revisionLabels.FINALIZED:Finalized`,
  AMENDED: $localize`:@@journal.revisionLabels.AMENDED:Amended`,
  EXECUTED: $localize`:@@journal.revisionLabels.EXECUTED:Marked executed`,
  CANCELLED: $localize`:@@journal.revisionLabels.CANCELLED:Cancelled`,
  EVIDENCE_ATTACHED: $localize`:@@journal.revisionLabels.EVIDENCE_ATTACHED:Evidence attached`,
  EVIDENCE_REMOVED: $localize`:@@journal.revisionLabels.EVIDENCE_REMOVED:Evidence removed`,
  TRANSACTION_LINKED: $localize`:@@journal.revisionLabels.TRANSACTION_LINKED:Transaction linked`,
  TRANSACTION_UNLINKED: $localize`:@@journal.revisionLabels.TRANSACTION_UNLINKED:Transaction unlinked`,
};

/** Fields fixed once a decision is finalized (they describe what the snapshot captured). */
export const LOCKED_FIELDS = [
  'portfolioId',
  'instrumentId',
  'decisionType',
  'decisionDate',
  'intendedQuantity',
  'intendedAllocationPct',
  'intendedPrice',
] as const;

export interface DecisionSummary {
  id: string;
  portfolioId: string | null;
  portfolioName: string | null;
  instrumentId: string | null;
  symbol: string | null;
  decisionType: DecisionType;
  status: DecisionStatus;
  decisionDate: string;
  title: string;
  reviewDate: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface DecisionContent {
  portfolioId: string | null;
  instrumentId: string | null;
  decisionType: DecisionType;
  decisionDate: string | null;
  title: string;
  rationale: string | null;
  expectedOutcome: string | null;
  timeHorizon: string | null;
  mainAssumptions: string | null;
  risks: string | null;
  invalidationConditions: string | null;
  reviewDate: string | null;
  notes: string | null;
  intendedQuantity: number | null;
  intendedAllocationPct: number | null;
  intendedPrice: number | null;
}

export interface DecisionRequest extends DecisionContent {
  expectedVersion?: number | null;
  amendmentReason?: string | null;
}

export interface SnapshotHolding {
  instrumentId: string;
  symbol: string;
  name: string;
  instrumentType: string;
  quantity: number;
  averageCost: number;
  costBasis: number;
  costWeightPct: number | null;
  valuationStatus: string;
  latestPrice: number | null;
  priceAsOf: string | null;
  priceSource: string | null;
  marketValue: number | null;
  marketWeightPct: number | null;
}

export interface SnapshotAllocationEntry {
  key: string;
  label: string;
  value: number;
  weightPct: number | null;
}

export interface SnapshotPortfolio {
  portfolioId: string;
  name: string;
  currency: string;
  valuationStatus: string;
  openHoldings: number;
  transactionCount: number;
  totalCostBasis: number;
  realizedPnl: number;
  totalMarketValue: number | null;
  unrealizedPnl: number | null;
  pricedMarketValue: number | null;
  pricedCoveragePct: number | null;
  oldestPriceAsOf: string | null;
  holdings: SnapshotHolding[];
  costBasisAllocation: SnapshotAllocationEntry[] | null;
  marketValueAllocation: SnapshotAllocationEntry[] | null;
  marketValueUnavailableReason: string | null;
}

export interface SnapshotPosition {
  instrumentId: string;
  symbol: string;
  held: boolean;
  quantity: number | null;
  averageCost: number | null;
  costBasis: number | null;
  costWeightPct: number | null;
  marketValue: number | null;
  marketWeightPct: number | null;
  latestPrice: number | null;
  priceAsOf: string | null;
  priceSource: string | null;
}

export interface SnapshotGoal {
  id: string;
  name: string;
  targetAmount: number | null;
  currency: string | null;
  targetDate: string | null;
  priority: string | null;
}

export interface SnapshotInvestor {
  configured: boolean;
  riskTolerance: string | null;
  investmentHorizon: string | null;
  experienceLevel: string | null;
  objectives: string | null;
  restrictions: string | null;
  liquidityNeeds: string | null;
  goals: SnapshotGoal[];
}

/** Stored once at finalization; never recalculated. */
export interface DecisionSnapshot {
  takenAt: string;
  valuationDate: string;
  portfolio: SnapshotPortfolio | null;
  position: SnapshotPosition | null;
  missingPrices: string[];
  investor: SnapshotInvestor;
  limitations: string[];
}

export interface DecisionEvidence {
  id: string;
  kind: EvidenceKind;
  role: EvidenceRole;
  sourceId: string;
  /** False once the source was deleted (or a cited chunk is no longer current); the copies remain. */
  available: boolean;
  title: string | null;
  publisher: string | null;
  publishedOn: string | null;
  location: string | null;
  excerpt: string | null;
  note: string | null;
  attachedAt: string;
  removedAt: string | null;
  removedReason: string | null;
}

export interface TransactionLink {
  id: string;
  transactionId: string;
  portfolioId: string;
  symbol: string;
  transactionType: string;
  tradeDate: string;
  quantity: number;
  price: number;
  note: string | null;
  linkedAt: string;
  removedAt: string | null;
  removedReason: string | null;
  state: LinkState;
  current: Transaction | null;
}

export interface DecisionDetail extends DecisionContent {
  id: string;
  portfolioName: string | null;
  symbol: string | null;
  status: DecisionStatus;
  finalizedAt: string | null;
  executedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  snapshot: DecisionSnapshot | null;
  evidence: DecisionEvidence[];
  transactions: TransactionLink[];
  actions: DecisionAction[];
  disclaimer: string;
}

export interface DecisionRevision {
  sequence: number;
  kind: RevisionKind;
  reason: string | null;
  statusAfter: DecisionStatus;
  changes: Record<string, unknown>;
  createdAt: string;
}

export interface DecisionFilters {
  ticker?: string;
  portfolioId?: string;
  status?: DecisionStatus | '';
  type?: DecisionType | '';
  from?: string;
  to?: string;
}

export interface EvidenceRequest {
  kind: EvidenceKind;
  sourceId: string;
  role: EvidenceRole;
  note?: string | null;
  expectedVersion: number;
}

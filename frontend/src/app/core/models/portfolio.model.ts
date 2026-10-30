/** Portfolio, instrument and investor-profile models (Phase 4). Amounts arrive as JSON numbers. */

export type InstrumentType = 'STOCK' | 'ETF' | 'FUND' | 'BOND' | 'OTHER';
export type Exchange = 'HOSE' | 'HNX' | 'UPCOM' | 'OTHER';
export type TransactionType = 'BUY' | 'SELL' | 'OPENING_POSITION';
/** AVAILABLE: every holding priced; STALE: priced but some prices old; PARTIAL: some unpriced; UNAVAILABLE: none priced. */
export type ValuationStatus = 'AVAILABLE' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE';
export type AllocationBasis = 'COST_BASIS' | 'MARKET_VALUE';

export interface Instrument {
  id: string;
  symbol: string;
  name: string;
  instrumentType: InstrumentType;
  exchange: Exchange | null;
  currency: string;
  aliases: string[];
}

export interface InstrumentRequest {
  symbol: string;
  name: string;
  instrumentType: InstrumentType;
  exchange?: Exchange | null;
  currency?: string | null;
  aliases?: string[];
}

/** A manually entered price or NAV observation; Investary has no market data feed. */
export interface PriceObservation {
  id: string;
  instrumentId: string;
  price: number;
  asOf: string;
  source: string;
  note: string | null;
  createdAt: string;
}

export interface PriceRequest {
  price: number;
  asOf: string;
  source: string;
  note?: string | null;
}

export interface Portfolio {
  id: string;
  name: string;
  description: string | null;
  baseCurrency: string;
  transactionCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface PortfolioRequest {
  name: string;
  description?: string | null;
  baseCurrency?: string | null;
}

export interface Transaction {
  id: string;
  portfolioId: string;
  instrumentId: string;
  symbol: string;
  instrumentName: string;
  type: TransactionType;
  tradeDate: string;
  quantity: number;
  price: number;
  fees: number;
  taxes: number;
  grossAmount: number;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TransactionRequest {
  instrumentId: string;
  type: TransactionType;
  tradeDate: string;
  quantity: number;
  price: number;
  fees?: number | null;
  taxes?: number | null;
  notes?: string | null;
}

/** An open position. Market value and unrealized P&L are null without a price observation. */
export interface Holding {
  instrumentId: string;
  symbol: string;
  name: string;
  instrumentType: InstrumentType;
  exchange: Exchange | null;
  quantity: number;
  averageCost: number;
  costBasis: number;
  realizedPnl: number;
  firstTradeDate: string;
  lastTradeDate: string;
  costWeightPct: number | null;
  valuationStatus: Exclude<ValuationStatus, 'PARTIAL'>;
  latestPrice: number | null;
  priceAsOf: string | null;
  priceSource: string | null;
  priceAgeDays: number | null;
  marketValue: number | null;
  unrealizedPnl: number | null;
  unrealizedPnlPct: number | null;
  marketWeightPct: number | null;
}

export interface PortfolioSummary {
  portfolioId: string;
  name: string;
  currency: string;
  openHoldings: number;
  closedPositions: number;
  transactionCount: number;
  totalCostBasis: number;
  realizedPnl: number;
  valuationStatus: ValuationStatus;
  pricedHoldings: number;
  stalePricedHoldings: number;
  pricedCostBasis: number;
  pricedMarketValue: number | null;
  pricedUnrealizedPnl: number | null;
  pricedCoveragePct: number | null;
  /** Only set when every holding has a price. */
  totalMarketValue: number | null;
  unrealizedPnl: number | null;
  unrealizedPnlPct: number | null;
  valuationDate: string;
  oldestPriceAsOf: string | null;
  priceStaleAfterDays: number;
  limitations: string[];
}

export interface AllocationSlice {
  key: string;
  label: string;
  value: number;
  weightPct: number | null;
}

export interface AllocationBreakdown {
  basis: AllocationBasis;
  total: number;
  byInstrument: AllocationSlice[];
  byInstrumentType: AllocationSlice[];
}

export interface Allocation {
  portfolioId: string;
  currency: string;
  valuationStatus: ValuationStatus;
  costBasis: AllocationBreakdown;
  marketValue: AllocationBreakdown | null;
  marketValueUnavailableReason: string | null;
  limitations: string[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type RiskTolerance = 'LOW' | 'MODERATE' | 'HIGH';
export type ExperienceLevel = 'NONE' | 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';
export type InvestmentHorizon = 'SHORT' | 'MEDIUM' | 'LONG';
export type GoalPriority = 'HIGH' | 'MEDIUM' | 'LOW';

export interface InvestorProfile {
  configured: boolean;
  experienceLevel: ExperienceLevel | null;
  riskTolerance: RiskTolerance | null;
  objectives: string | null;
  investmentHorizon: InvestmentHorizon | null;
  preferredInstruments: string[];
  restrictions: string | null;
  liquidityNeeds: string | null;
  notes: string | null;
  updatedAt: string | null;
}

export type ProfileRequest = Omit<InvestorProfile, 'configured' | 'updatedAt'>;

export interface Goal {
  id: string;
  name: string;
  description: string | null;
  targetAmount: number | null;
  targetDate: string | null;
  currency: string;
  priority: GoalPriority;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export type GoalRequest = Pick<Goal, 'name' | 'description' | 'targetAmount' | 'targetDate' | 'currency' | 'priority' | 'notes'>;

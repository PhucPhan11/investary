package com.investary.journal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The portfolio and investor context stored once when a decision is finalized. Every figure is copied from the
 * portfolio and investor query services (Phase 4 calculations, {@code Money} rounding); nothing is estimated. Market
 * values are {@code null} wherever no price observation existed.
 *
 * @param portfolio      {@code null} for a watchlist decision
 * @param position       the decision instrument's position, or {@code null} for an instrument-less rebalance
 * @param missingPrices  symbols of open holdings that had no price observation
 * @param limitations    plain-language limits of these figures
 */
public record DecisionSnapshot(Instant takenAt, LocalDate valuationDate, PortfolioState portfolio, Position position,
                               List<String> missingPrices, InvestorState investor, List<String> limitations) {

    /**
     * @param totalMarketValue  only when every open holding has a price
     * @param pricedMarketValue value of the priced holdings only
     */
    public record PortfolioState(UUID portfolioId, String name, String currency, String valuationStatus, int openHoldings,
                                 long transactionCount, BigDecimal totalCostBasis, BigDecimal realizedPnl,
                                 BigDecimal totalMarketValue, BigDecimal unrealizedPnl, BigDecimal pricedMarketValue,
                                 BigDecimal pricedCoveragePct, LocalDate oldestPriceAsOf, List<Holding> holdings,
                                 List<AllocationEntry> costBasisAllocation, List<AllocationEntry> marketValueAllocation,
                                 String marketValueUnavailableReason) {
    }

    public record Holding(UUID instrumentId, String symbol, String name, String instrumentType, BigDecimal quantity,
                          BigDecimal averageCost, BigDecimal costBasis, BigDecimal costWeightPct, String valuationStatus,
                          BigDecimal latestPrice, LocalDate priceAsOf, String priceSource, BigDecimal marketValue,
                          BigDecimal marketWeightPct) {
    }

    public record AllocationEntry(String key, String label, BigDecimal value, BigDecimal weightPct) {
    }

    /**
     * @param held       whether the portfolio held the instrument at the snapshot
     * @param latestPrice latest price observation on or before the valuation date, {@code null} if none
     */
    public record Position(UUID instrumentId, String symbol, boolean held, BigDecimal quantity, BigDecimal averageCost,
                           BigDecimal costBasis, BigDecimal costWeightPct, BigDecimal marketValue, BigDecimal marketWeightPct,
                           BigDecimal latestPrice, LocalDate priceAsOf, String priceSource) {
    }

    public record InvestorState(boolean configured, String riskTolerance, String investmentHorizon, String experienceLevel,
                                String objectives, String restrictions, String liquidityNeeds, List<Goal> goals) {
    }

    public record Goal(UUID id, String name, BigDecimal targetAmount, String currency, LocalDate targetDate, String priority) {
    }
}

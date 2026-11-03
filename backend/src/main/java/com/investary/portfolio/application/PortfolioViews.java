package com.investary.portfolio.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.Instrument;
import com.investary.portfolio.domain.InstrumentPrice;
import com.investary.portfolio.domain.InstrumentType;
import com.investary.portfolio.domain.Portfolio;
import com.investary.portfolio.domain.TransactionType;

/**
 * Read models returned by the portfolio services. Computed amounts are rounded for display with
 * {@link Money}; nothing here is a forecast or an estimate.
 */
public final class PortfolioViews {

    private PortfolioViews() {
    }

    public record InstrumentView(UUID id, String symbol, String name, InstrumentType instrumentType, Exchange exchange,
                                 String currency, List<String> aliases) {

        static InstrumentView of(Instrument instrument) {
            return new InstrumentView(instrument.getId(), instrument.getSymbol(), instrument.getName(), instrument.getInstrumentType(),
                    instrument.getExchange(), instrument.getCurrency(), instrument.getAliases());
        }
    }

    /**
     * @param source where the user read the value; Investary has no market data feed
     */
    public record PriceView(UUID id, UUID instrumentId, BigDecimal price, LocalDate asOf, String source, String note,
                            Instant createdAt) {

        static PriceView of(InstrumentPrice price) {
            return new PriceView(price.getId(), price.getInstrumentId(), price.getPrice(), price.getAsOf(), price.getSource(),
                    price.getNote(), price.getCreatedAt());
        }
    }

    public record PortfolioView(UUID id, String name, String description, String baseCurrency, long transactionCount,
                                Instant createdAt, Instant updatedAt) {

        static PortfolioView of(Portfolio portfolio, long transactionCount) {
            return new PortfolioView(portfolio.getId(), portfolio.getName(), portfolio.getDescription(), portfolio.getBaseCurrency(),
                    transactionCount, portfolio.getCreatedAt(), portfolio.getUpdatedAt());
        }
    }

    /**
     * @param grossAmount quantity × price, before fees and taxes
     */
    public record TransactionView(UUID id, UUID portfolioId, UUID instrumentId, String symbol, String instrumentName,
                                  TransactionType type, LocalDate tradeDate, BigDecimal quantity, BigDecimal price,
                                  BigDecimal fees, BigDecimal taxes, BigDecimal grossAmount, String notes,
                                  Instant createdAt, Instant updatedAt) {
    }

    public enum ValuationStatus {
        /** Every open holding has a price observation that is not stale. */
        AVAILABLE,
        /** Every open holding has a price, but at least one is older than the staleness limit. */
        STALE,
        /** Some open holdings have no price observation; totals cover the priced holdings only. */
        PARTIAL,
        /** No open holding has a price observation (or there are no open holdings). */
        UNAVAILABLE
    }

    /**
     * One open position. Market value and unrealized P&amp;L are {@code null} unless a price observation
     * exists; they are never estimated.
     *
     * @param averageCost       cost basis ÷ quantity, fees included
     * @param costWeightPct     share of the portfolio's total cost basis
     * @param valuationStatus   AVAILABLE, STALE or UNAVAILABLE for this holding
     * @param priceAgeDays      days between the price date and the valuation date
     */
    public record HoldingView(UUID instrumentId, String symbol, String name, InstrumentType instrumentType, Exchange exchange,
                              BigDecimal quantity, BigDecimal averageCost, BigDecimal costBasis, BigDecimal realizedPnl,
                              LocalDate firstTradeDate, LocalDate lastTradeDate, BigDecimal costWeightPct,
                              ValuationStatus valuationStatus, BigDecimal latestPrice, LocalDate priceAsOf, String priceSource,
                              Long priceAgeDays, BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal unrealizedPnlPct,
                              BigDecimal marketWeightPct) {
    }

    /**
     * Portfolio totals. {@code totalMarketValue} and {@code unrealizedPnl} are only set when every open
     * holding has a price (status AVAILABLE or STALE); otherwise the {@code priced*} fields show what the
     * priced subset is worth and {@code pricedCoveragePct} how much of the cost basis that subset covers.
     *
     * @param realizedPnl         realized result of all sells, including closed positions
     * @param valuationDate       the date prices are taken on or before (today, Asia/Ho_Chi_Minh)
     * @param oldestPriceAsOf     the oldest price date used
     * @param limitations         plain-language limits of these figures
     */
    public record PortfolioSummary(UUID portfolioId, String name, String currency, int openHoldings, int closedPositions,
                                   long transactionCount, BigDecimal totalCostBasis, BigDecimal realizedPnl,
                                   ValuationStatus valuationStatus, int pricedHoldings, int stalePricedHoldings,
                                   BigDecimal pricedCostBasis, BigDecimal pricedMarketValue, BigDecimal pricedUnrealizedPnl,
                                   BigDecimal pricedCoveragePct, BigDecimal totalMarketValue, BigDecimal unrealizedPnl,
                                   BigDecimal unrealizedPnlPct, LocalDate valuationDate, LocalDate oldestPriceAsOf,
                                   int priceStaleAfterDays, List<String> limitations) {
    }

    public enum AllocationBasis {
        /** Weights by money invested (cost basis). Always available; not a market-value allocation. */
        COST_BASIS,
        /** Weights by market value from manual price observations; only when every holding is priced. */
        MARKET_VALUE
    }

    public record AllocationSlice(String key, String label, BigDecimal value, BigDecimal weightPct) {
    }

    public record AllocationBreakdown(AllocationBasis basis, BigDecimal total, List<AllocationSlice> byInstrument,
                                      List<AllocationSlice> byInstrumentType) {
    }

    /**
     * @param marketValue                 {@code null} unless every holding has a price
     * @param marketValueUnavailableReason why the market-value view is missing
     */
    public record Allocation(UUID portfolioId, String currency, ValuationStatus valuationStatus, AllocationBreakdown costBasis,
                             AllocationBreakdown marketValue, String marketValueUnavailableReason, List<String> limitations) {
    }

    /**
     * Everything the read-only intelligence module may know about a portfolio at one moment.
     */
    public record PortfolioSnapshot(PortfolioView portfolio, PortfolioSummary summary, List<HoldingView> holdings,
                                    List<InstrumentView> instruments) {
    }
}

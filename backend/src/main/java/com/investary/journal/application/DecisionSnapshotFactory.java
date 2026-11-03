package com.investary.journal.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.investary.investor.application.GoalView;
import com.investary.investor.application.ProfileView;
import com.investary.journal.application.DecisionSnapshot.AllocationEntry;
import com.investary.journal.application.DecisionSnapshot.Goal;
import com.investary.journal.application.DecisionSnapshot.Holding;
import com.investary.journal.application.DecisionSnapshot.InvestorState;
import com.investary.journal.application.DecisionSnapshot.PortfolioState;
import com.investary.journal.application.DecisionSnapshot.Position;
import com.investary.portfolio.application.PortfolioViews.Allocation;
import com.investary.portfolio.application.PortfolioViews.AllocationBreakdown;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.application.PortfolioViews.ValuationStatus;

/**
 * Builds a {@link DecisionSnapshot} from the read-only portfolio and investor views. It only copies values; it never
 * computes a valuation, so a holding without a price observation has no market value here either.
 */
final class DecisionSnapshotFactory {

    private DecisionSnapshotFactory() {
    }

    /**
     * @param portfolio   the portfolio snapshot, or {@code null} for a watchlist decision
     * @param allocation  the portfolio's allocation, or {@code null} for a watchlist decision
     * @param latestPrice the instrument's latest price observation on or before {@code today}, or {@code null}
     * @param portfolioNote a limitation to record, e.g. that the portfolio was deleted before finalization
     */
    static DecisionSnapshot build(Instant takenAt, LocalDate today, PortfolioSnapshot portfolio, Allocation allocation,
                                  UUID instrumentId, String symbol, PriceView latestPrice, ProfileView profile, List<GoalView> goals,
                                  String portfolioNote) {
        List<String> limitations = new ArrayList<>();
        if (portfolioNote != null) {
            limitations.add(portfolioNote);
        }
        PortfolioState state = null;
        List<String> missingPrices = List.of();
        LocalDate valuationDate = today;
        HoldingView held = null;
        if (portfolio != null) {
            var summary = portfolio.summary();
            valuationDate = summary.valuationDate();
            List<Holding> holdings = portfolio.holdings().stream().map(DecisionSnapshotFactory::holding).toList();
            missingPrices = portfolio.holdings().stream()
                    .filter(h -> h.valuationStatus() == ValuationStatus.UNAVAILABLE)
                    .map(HoldingView::symbol)
                    .toList();
            state = new PortfolioState(summary.portfolioId(), summary.name(), summary.currency(), name(summary.valuationStatus()),
                    summary.openHoldings(), summary.transactionCount(), summary.totalCostBasis(), summary.realizedPnl(),
                    summary.totalMarketValue(), summary.unrealizedPnl(), summary.pricedMarketValue(), summary.pricedCoveragePct(),
                    summary.oldestPriceAsOf(), holdings, entries(allocation == null ? null : allocation.costBasis()),
                    entries(allocation == null ? null : allocation.marketValue()),
                    allocation == null ? null : allocation.marketValueUnavailableReason());
            limitations.addAll(summary.limitations());
            if (!missingPrices.isEmpty()) {
                limitations.add("No price observation for " + String.join(", ", missingPrices)
                        + "; their market value is unknown and was not estimated.");
            }
            if (instrumentId != null) {
                held = portfolio.holdings().stream().filter(h -> h.instrumentId().equals(instrumentId)).findFirst().orElse(null);
            }
        } else if (portfolioNote == null) {
            limitations.add("Watchlist decision: no portfolio was linked, so no holdings were recorded.");
        }

        Position position = null;
        if (instrumentId != null) {
            position = held != null
                    ? new Position(instrumentId, held.symbol(), true, held.quantity(), held.averageCost(), held.costBasis(),
                            held.costWeightPct(), held.marketValue(), held.marketWeightPct(), held.latestPrice(), held.priceAsOf(),
                            held.priceSource())
                    : new Position(instrumentId, symbol, false, null, null, null, null, null, null,
                            latestPrice == null ? null : latestPrice.price(), latestPrice == null ? null : latestPrice.asOf(),
                            latestPrice == null ? null : latestPrice.source());
            if (position.latestPrice() == null) {
                limitations.add("No price observation for " + position.symbol() + " on or before " + valuationDate + ".");
            }
        }

        if (!profile.configured()) {
            limitations.add("The investor profile was not configured.");
        }
        InvestorState investor = new InvestorState(profile.configured(), name(profile.riskTolerance()), name(profile.investmentHorizon()),
                name(profile.experienceLevel()), profile.objectives(), profile.restrictions(), profile.liquidityNeeds(),
                goals.stream().map(g -> new Goal(g.id(), g.name(), g.targetAmount(), g.currency(), g.targetDate(), name(g.priority())))
                        .toList());
        return new DecisionSnapshot(takenAt, valuationDate, state, position, missingPrices, investor, List.copyOf(limitations));
    }

    private static Holding holding(HoldingView h) {
        return new Holding(h.instrumentId(), h.symbol(), h.name(), name(h.instrumentType()), h.quantity(), h.averageCost(), h.costBasis(),
                h.costWeightPct(), name(h.valuationStatus()), h.latestPrice(), h.priceAsOf(), h.priceSource(), h.marketValue(),
                h.marketWeightPct());
    }

    private static List<AllocationEntry> entries(AllocationBreakdown breakdown) {
        if (breakdown == null) {
            return null;
        }
        return breakdown.byInstrument().stream().map(s -> new AllocationEntry(s.key(), s.label(), s.value(), s.weightPct())).toList();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

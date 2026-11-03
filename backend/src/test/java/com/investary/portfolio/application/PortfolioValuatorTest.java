package com.investary.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.portfolio.application.HoldingCalculator.Position;
import com.investary.portfolio.application.PortfolioValuator.Valuation;
import com.investary.portfolio.application.PortfolioViews.AllocationBasis;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.application.PortfolioViews.ValuationStatus;
import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;

class PortfolioValuatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-30");
    private static final UUID FPT = new UUID(0, 1);
    private static final UUID ETF = new UUID(0, 2);
    private static final UUID SOLD = new UUID(0, 3);

    private final Map<UUID, InstrumentView> instruments = Map.of(
            FPT, new InstrumentView(FPT, "FPT", "Công ty FPT", InstrumentType.STOCK, Exchange.HOSE, "VND", List.of()),
            ETF, new InstrumentView(ETF, "E1VFVN30", "Quỹ ETF VN30", InstrumentType.ETF, Exchange.HOSE, "VND", List.of()),
            SOLD, new InstrumentView(SOLD, "HPG", "Hòa Phát", InstrumentType.STOCK, Exchange.HOSE, "VND", List.of()));

    @Test
    void withoutPricesThereIsNoMarketValueOrUnrealizedPnl() {
        Valuation valuation = value(Map.of());

        assertThat(valuation.summary().valuationStatus()).isEqualTo(ValuationStatus.UNAVAILABLE);
        assertThat(valuation.summary().totalMarketValue()).isNull();
        assertThat(valuation.summary().unrealizedPnl()).isNull();
        assertThat(valuation.summary().pricedMarketValue()).isNull();
        assertThat(valuation.holdings()).allSatisfy(holding -> {
            assertThat(holding.marketValue()).isNull();
            assertThat(holding.unrealizedPnl()).isNull();
            assertThat(holding.valuationStatus()).isEqualTo(ValuationStatus.UNAVAILABLE);
        });
        assertThat(valuation.summary().totalCostBasis()).isEqualByComparingTo("30000000");
        assertThat(valuation.summary().realizedPnl()).isEqualByComparingTo("500000");
        assertThat(valuation.allocation().marketValue()).isNull();
        assertThat(valuation.allocation().marketValueUnavailableReason()).contains("2/2");
        assertThat(valuation.allocation().costBasis().basis()).isEqualTo(AllocationBasis.COST_BASIS);
    }

    @Test
    void partialPricesGiveTotalsOnlyForThePricedSubset() {
        Valuation valuation = value(Map.of(FPT, price(FPT, "110000", TODAY.minusDays(1))));

        assertThat(valuation.summary().valuationStatus()).isEqualTo(ValuationStatus.PARTIAL);
        assertThat(valuation.summary().totalMarketValue()).isNull();
        assertThat(valuation.summary().unrealizedPnl()).isNull();
        assertThat(valuation.summary().pricedMarketValue()).isEqualByComparingTo("22000000");
        assertThat(valuation.summary().pricedUnrealizedPnl()).isEqualByComparingTo("2000000");
        assertThat(valuation.summary().pricedCoveragePct()).isEqualByComparingTo("66.67");
        HoldingView fpt = holding(valuation, "FPT");
        assertThat(fpt.marketValue()).isEqualByComparingTo("22000000");
        assertThat(fpt.unrealizedPnlPct()).isEqualByComparingTo("10.00");
        assertThat(fpt.marketWeightPct()).isNull();
        assertThat(holding(valuation, "E1VFVN30").marketValue()).isNull();
    }

    @Test
    void completePricesGiveMarketValueAllocation() {
        Valuation valuation = value(Map.of(
                FPT, price(FPT, "110000", TODAY),
                ETF, price(ETF, "20000", TODAY.minusDays(3))));

        assertThat(valuation.summary().valuationStatus()).isEqualTo(ValuationStatus.AVAILABLE);
        assertThat(valuation.summary().totalMarketValue()).isEqualByComparingTo("32000000");
        assertThat(valuation.summary().unrealizedPnl()).isEqualByComparingTo("2000000");
        assertThat(valuation.allocation().marketValue().basis()).isEqualTo(AllocationBasis.MARKET_VALUE);
        assertThat(valuation.allocation().marketValue().byInstrument()).extracting(s -> s.key()).containsExactly("FPT", "E1VFVN30");
        assertThat(valuation.allocation().marketValue().byInstrument().getFirst().weightPct()).isEqualByComparingTo("68.75");
        assertThat(valuation.allocation().costBasis().byInstrumentType()).extracting(s -> s.key()).containsExactly("STOCK", "ETF");
        assertThat(valuation.allocation().costBasis().byInstrumentType().getFirst().weightPct()).isEqualByComparingTo("66.67");
    }

    @Test
    void oldPricesMarkTheValuationStaleButStillCount() {
        Valuation valuation = value(Map.of(
                FPT, price(FPT, "110000", TODAY.minusDays(8)),
                ETF, price(ETF, "20000", TODAY.minusDays(7))));

        assertThat(valuation.summary().valuationStatus()).isEqualTo(ValuationStatus.STALE);
        assertThat(valuation.summary().stalePricedHoldings()).isEqualTo(1);
        assertThat(valuation.summary().totalMarketValue()).isNotNull();
        assertThat(holding(valuation, "FPT").valuationStatus()).isEqualTo(ValuationStatus.STALE);
        assertThat(holding(valuation, "FPT").priceAgeDays()).isEqualTo(8);
        assertThat(holding(valuation, "E1VFVN30").valuationStatus()).isEqualTo(ValuationStatus.AVAILABLE);
        assertThat(valuation.summary().oldestPriceAsOf()).isEqualTo(TODAY.minusDays(8));
    }

    @Test
    void closedPositionsAreNotHoldingsButKeepRealizedPnl() {
        Valuation valuation = value(Map.of());

        assertThat(valuation.holdings()).extracting(HoldingView::symbol).containsExactly("FPT", "E1VFVN30");
        assertThat(valuation.summary().openHoldings()).isEqualTo(2);
        assertThat(valuation.summary().closedPositions()).isEqualTo(1);
        assertThat(holding(valuation, "FPT").costWeightPct()).isEqualByComparingTo("66.67");
        assertThat(valuation.summary().limitations()).isNotEmpty();
    }

    @Test
    void emptyPortfolioIsUnavailableWithZeroTotals() {
        Valuation valuation = PortfolioValuator.value(new UUID(9, 9), "Trống", "VND", 0, Map.of(), instruments, Map.of(), TODAY, 7);

        assertThat(valuation.summary().valuationStatus()).isEqualTo(ValuationStatus.UNAVAILABLE);
        assertThat(valuation.summary().totalCostBasis()).isEqualByComparingTo("0");
        assertThat(valuation.allocation().costBasis().byInstrument()).isEmpty();
        assertThat(valuation.allocation().marketValueUnavailableReason()).contains("chưa có vị thế");
    }

    private Valuation value(Map<UUID, PriceView> prices) {
        Map<UUID, Position> positions = new LinkedHashMap<>();
        positions.put(FPT, new Position(FPT, new BigDecimal("200"), new BigDecimal("20000000"), BigDecimal.ZERO,
                new BigDecimal("20000000"), BigDecimal.ZERO, TODAY.minusDays(100), TODAY.minusDays(50), 2));
        positions.put(ETF, new Position(ETF, new BigDecimal("500"), new BigDecimal("10000000"), BigDecimal.ZERO,
                new BigDecimal("10000000"), BigDecimal.ZERO, TODAY.minusDays(90), TODAY.minusDays(90), 1));
        positions.put(SOLD, new Position(SOLD, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500000"),
                new BigDecimal("1000000"), new BigDecimal("1500000"), TODAY.minusDays(80), TODAY.minusDays(70), 2));
        return PortfolioValuator.value(new UUID(9, 9), "Danh mục chính", "VND", 5, positions, instruments, prices, TODAY, 7);
    }

    private static PriceView price(UUID instrument, String value, LocalDate asOf) {
        return new PriceView(UUID.randomUUID(), instrument, new BigDecimal(value), asOf, "Bảng giá (tổng hợp)", null, Instant.EPOCH);
    }

    private static HoldingView holding(Valuation valuation, String symbol) {
        return valuation.holdings().stream().filter(h -> h.symbol().equals(symbol)).findFirst().orElseThrow();
    }
}

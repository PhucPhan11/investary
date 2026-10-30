package com.investary.portfolio.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.investary.portfolio.application.HoldingCalculator.Position;
import com.investary.portfolio.application.PortfolioViews.Allocation;
import com.investary.portfolio.application.PortfolioViews.AllocationBasis;
import com.investary.portfolio.application.PortfolioViews.AllocationBreakdown;
import com.investary.portfolio.application.PortfolioViews.AllocationSlice;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.application.PortfolioViews.ValuationStatus;

/**
 * Turns positions and the latest manual price observations into holdings, a summary and allocations.
 * Pure: no I/O, no clock. Market values are computed only from real observations; a holding without a
 * price has no market value and no unrealized P&amp;L, and portfolio totals are only given when every
 * holding is priced.
 */
public final class PortfolioValuator {

    static final List<String> LIMITATIONS = List.of(
            "Giá vốn bình quân gia quyền, đã gồm phí và thuế khi mua.",
            "Giá thị trường là giá bạn nhập thủ công kèm nguồn và ngày; Investary không có dữ liệu thị trường trực tuyến.",
            "Chưa xử lý sự kiện doanh nghiệp (chia tách, cổ phiếu thưởng, quyền mua, cổ tức); hãy nhập điều chỉnh thủ công.",
            "Phân bổ theo mã và loại tài sản; không phân tích theo ngành hay thành phần quỹ ETF.");

    private PortfolioValuator() {
    }

    public record Valuation(PortfolioSummary summary, List<HoldingView> holdings, Allocation allocation) {
    }

    public static Valuation value(UUID portfolioId, String name, String currency, long transactionCount,
                                  Map<UUID, Position> positions, Map<UUID, InstrumentView> instruments,
                                  Map<UUID, PriceView> latestPrices, LocalDate valuationDate, int staleAfterDays) {
        List<Position> open = positions.values().stream().filter(Position::isOpen).toList();
        int closed = (int) positions.values().stream().filter(p -> !p.isOpen() && p.tradeCount() > 0).count();

        BigDecimal totalCost = sum(open.stream().map(Position::costBasis).toList());
        BigDecimal realized = sum(positions.values().stream().map(Position::realizedPnl).toList());

        List<Row> rows = new ArrayList<>();
        for (Position position : open) {
            InstrumentView instrument = instruments.get(position.instrumentId());
            PriceView price = latestPrices.get(position.instrumentId());
            ValuationStatus status = ValuationStatus.UNAVAILABLE;
            BigDecimal marketValue = null;
            Long age = null;
            if (price != null) {
                age = ChronoUnit.DAYS.between(price.asOf(), valuationDate);
                status = age > staleAfterDays ? ValuationStatus.STALE : ValuationStatus.AVAILABLE;
                marketValue = position.quantity().multiply(price.price(), HoldingCalculator.MC);
            }
            rows.add(new Row(position, instrument, price, status, marketValue, age));
        }
        rows.sort(Comparator.comparing((Row row) -> row.position().costBasis()).reversed()
                .thenComparing(row -> row.instrument().symbol()));

        List<Row> priced = rows.stream().filter(row -> row.marketValue() != null).toList();
        int stale = (int) rows.stream().filter(row -> row.status() == ValuationStatus.STALE).count();
        ValuationStatus status;
        if (rows.isEmpty() || priced.isEmpty()) {
            status = ValuationStatus.UNAVAILABLE;
        } else if (priced.size() < rows.size()) {
            status = ValuationStatus.PARTIAL;
        } else {
            status = stale > 0 ? ValuationStatus.STALE : ValuationStatus.AVAILABLE;
        }
        boolean complete = status == ValuationStatus.AVAILABLE || status == ValuationStatus.STALE;

        BigDecimal pricedCost = sum(priced.stream().map(row -> row.position().costBasis()).toList());
        BigDecimal pricedValue = priced.isEmpty() ? null : sum(priced.stream().map(Row::marketValue).toList());
        BigDecimal pricedPnl = pricedValue == null ? null : pricedValue.subtract(pricedCost, HoldingCalculator.MC);
        BigDecimal totalValue = complete ? pricedValue : null;

        List<HoldingView> holdings = rows.stream().map(row -> holding(row, currency, totalCost, totalValue)).toList();
        LocalDate oldest = priced.stream().map(row -> row.price().asOf()).min(Comparator.naturalOrder()).orElse(null);

        PortfolioSummary summary = new PortfolioSummary(portfolioId, name, currency, rows.size(), closed, transactionCount,
                Money.amount(totalCost, currency), Money.amount(realized, currency), status, priced.size(), stale,
                Money.amount(pricedCost, currency), Money.amount(pricedValue, currency), Money.amount(pricedPnl, currency),
                Money.percent(pricedCost, totalCost), Money.amount(totalValue, currency),
                complete ? Money.amount(pricedPnl, currency) : null, complete ? Money.percent(pricedPnl, totalCost) : null,
                valuationDate, oldest, staleAfterDays, LIMITATIONS);

        AllocationBreakdown costBreakdown = breakdown(AllocationBasis.COST_BASIS, rows, row -> row.position().costBasis(), currency);
        AllocationBreakdown valueBreakdown = complete ? breakdown(AllocationBasis.MARKET_VALUE, rows, Row::marketValue, currency) : null;
        String reason = complete ? null : rows.isEmpty() ? "Danh mục chưa có vị thế mở."
                : "Chưa có giá cho " + (rows.size() - priced.size()) + "/" + rows.size()
                        + " mã; phân bổ theo giá trị thị trường chỉ hiển thị khi mọi mã đều có giá.";
        Allocation allocation = new Allocation(portfolioId, currency, status, costBreakdown, valueBreakdown, reason, LIMITATIONS);
        return new Valuation(summary, holdings, allocation);
    }

    private static HoldingView holding(Row row, String currency, BigDecimal totalCost, BigDecimal totalValue) {
        Position position = row.position();
        InstrumentView instrument = row.instrument();
        BigDecimal pnl = row.marketValue() == null ? null : row.marketValue().subtract(position.costBasis(), HoldingCalculator.MC);
        return new HoldingView(instrument.id(), instrument.symbol(), instrument.name(), instrument.instrumentType(),
                instrument.exchange(), Money.quantity(position.quantity()), Money.unitPrice(position.averageCost()),
                Money.amount(position.costBasis(), currency), Money.amount(position.realizedPnl(), currency),
                position.firstTradeDate(), position.lastTradeDate(), Money.percent(position.costBasis(), totalCost),
                row.status(), row.price() == null ? null : row.price().price(), row.price() == null ? null : row.price().asOf(),
                row.price() == null ? null : row.price().source(), row.ageDays(), Money.amount(row.marketValue(), currency),
                Money.amount(pnl, currency), Money.percent(pnl, position.costBasis()),
                totalValue == null ? null : Money.percent(row.marketValue(), totalValue));
    }

    private static AllocationBreakdown breakdown(AllocationBasis basis, List<Row> rows, java.util.function.Function<Row, BigDecimal> value,
                                                 String currency) {
        BigDecimal total = sum(rows.stream().map(value).toList());
        List<AllocationSlice> byInstrument = rows.stream()
                .map(row -> new AllocationSlice(row.instrument().symbol(), row.instrument().name(),
                        Money.amount(value.apply(row), currency), Money.percent(value.apply(row), total)))
                .sorted(Comparator.comparing(AllocationSlice::value).reversed())
                .toList();
        Map<String, BigDecimal> types = new LinkedHashMap<>();
        rows.forEach(row -> types.merge(row.instrument().instrumentType().name(), value.apply(row),
                (a, b) -> a.add(b, HoldingCalculator.MC)));
        List<AllocationSlice> byType = types.entrySet().stream()
                .map(entry -> new AllocationSlice(entry.getKey(), entry.getKey(), Money.amount(entry.getValue(), currency),
                        Money.percent(entry.getValue(), total)))
                .sorted(Comparator.comparing(AllocationSlice::value).reversed())
                .toList();
        return new AllocationBreakdown(basis, Money.amount(total, currency), byInstrument, byType);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, (a, b) -> a.add(b, HoldingCalculator.MC));
    }

    private record Row(Position position, InstrumentView instrument, PriceView price, ValuationStatus status,
                       BigDecimal marketValue, Long ageDays) {
    }
}

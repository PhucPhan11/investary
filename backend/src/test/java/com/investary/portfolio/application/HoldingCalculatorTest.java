package com.investary.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.portfolio.application.HoldingCalculator.Oversell;
import com.investary.portfolio.application.HoldingCalculator.Position;
import com.investary.portfolio.application.HoldingCalculator.Trade;
import com.investary.portfolio.domain.TransactionType;

class HoldingCalculatorTest {

    private static final UUID FPT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VCB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private int sequence;

    @Test
    void weightedAverageCostIncludesFeesAndTaxes() {
        Position position = single(
                trade(FPT, TransactionType.BUY, "2026-01-05", "100", "100000", "150", "0"),
                trade(FPT, TransactionType.BUY, "2026-02-05", "300", "120000", "540", "10"));

        assertThat(position.quantity()).isEqualByComparingTo("400");
        // 100*100000 + 150 + 300*120000 + 540 + 10 = 46 000 700
        assertThat(position.costBasis()).isEqualByComparingTo("46000700");
        assertThat(Money.unitPrice(position.averageCost())).isEqualByComparingTo("115001.7500");
        assertThat(position.realizedPnl()).isZero();
    }

    @Test
    void openingPositionIsCostedLikeABuy() {
        Position position = single(trade(FPT, TransactionType.OPENING_POSITION, "2025-12-31", "1000", "95000", "0", "0"));

        assertThat(position.costBasis()).isEqualByComparingTo("95000000");
        assertThat(position.averageCost()).isEqualByComparingTo("95000");
    }

    @Test
    void partialSellRemovesProportionalCostAndRealizesNetResult() {
        Position position = single(
                trade(FPT, TransactionType.BUY, "2026-01-05", "300", "100000", "300", "0"),
                trade(FPT, TransactionType.SELL, "2026-03-01", "100", "130000", "195", "130"));

        // average cost 100 001; removed cost 100 * 100 001 = 10 000 100
        assertThat(position.quantity()).isEqualByComparingTo("200");
        assertThat(position.costBasis()).isEqualByComparingTo("20000200");
        assertThat(position.averageCost()).isEqualByComparingTo("100001");
        // net proceeds 13 000 000 - 325 = 12 999 675; realized 12 999 675 - 10 000 100
        assertThat(position.realizedPnl()).isEqualByComparingTo("2999575");
    }

    @Test
    void fullSellClosesThePositionWithZeroCost() {
        Position position = single(
                trade(FPT, TransactionType.BUY, "2026-01-05", "3", "10", "1", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-06", "3", "9", "0", "0"));

        assertThat(position.isOpen()).isFalse();
        assertThat(position.costBasis()).isZero();
        assertThat(position.averageCost()).isNull();
        assertThat(position.realizedPnl()).isEqualByComparingTo("-4");
    }

    @Test
    void repeatingDecimalsDoNotLeaveResidualCostWhenClosedInSteps() {
        List<Trade> trades = List.of(
                trade(FPT, TransactionType.BUY, "2026-01-05", "3", "10", "1", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-06", "1", "12", "0", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-07", "1", "12", "0", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-08", "1", "12", "0", "0"));

        Position position = HoldingCalculator.calculate(trades).get(FPT);

        assertThat(position.costBasis()).isZero();
        assertThat(Money.amount(position.realizedPnl(), "VND")).isEqualByComparingTo("5");
    }

    @Test
    void sellingMoreThanHeldIsRejected() {
        List<Trade> trades = List.of(
                trade(FPT, TransactionType.BUY, "2026-01-05", "100", "100", "0", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-06", "150", "100", "0", "0"));

        assertThatThrownBy(() -> HoldingCalculator.calculate(trades))
                .isInstanceOf(Oversell.class)
                .hasMessageContaining("FPT")
                .hasMessageContaining("2026-01-06")
                .satisfies(e -> assertThat(((Oversell) e).available()).isEqualByComparingTo("100"));
    }

    @Test
    void sellsAreCheckedAgainstHoldingsOnTheirOwnDateNotTheFinalTotal() {
        // The buy comes later in time than the sell even though it was entered first.
        List<Trade> trades = List.of(
                trade(FPT, TransactionType.BUY, "2026-02-01", "100", "100", "0", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-15", "50", "100", "0", "0"));

        assertThatThrownBy(() -> HoldingCalculator.calculate(trades)).isInstanceOf(Oversell.class);
    }

    @Test
    void sameDayTransactionsFollowEntryOrder() {
        List<Trade> buyThenSell = List.of(
                trade(FPT, TransactionType.BUY, "2026-01-05", "100", "100", "0", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-05", "100", "110", "0", "0"));
        assertThat(HoldingCalculator.calculate(buyThenSell).get(FPT).realizedPnl()).isEqualByComparingTo("1000");

        List<Trade> sellFirst = List.of(
                trade(FPT, TransactionType.SELL, "2026-01-05", "100", "110", "0", "0"),
                trade(FPT, TransactionType.BUY, "2026-01-05", "100", "100", "0", "0"));
        assertThatThrownBy(() -> HoldingCalculator.calculate(sellFirst)).isInstanceOf(Oversell.class);
    }

    @Test
    void editingOrDeletingAnEarlierTradeIsRecalculatedChronologically() {
        Trade buy = trade(FPT, TransactionType.BUY, "2026-01-05", "100", "100", "0", "0");
        Trade secondBuy = trade(FPT, TransactionType.BUY, "2026-01-10", "100", "200", "0", "0");
        Trade sell = trade(FPT, TransactionType.SELL, "2026-01-20", "150", "300", "0", "0");
        assertThat(HoldingCalculator.calculate(List.of(sell, secondBuy, buy)).get(FPT).realizedPnl())
                .isEqualByComparingTo("22500"); // 150 * (300 - 150)

        // Editing the first buy's price changes the average cost used by the later sell.
        Trade editedBuy = new Trade(buy.id(), FPT, "FPT", TransactionType.BUY, buy.tradeDate(), buy.createdAt(),
                new BigDecimal("100"), new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(HoldingCalculator.calculate(List.of(sell, secondBuy, editedBuy)).get(FPT).realizedPnl())
                .isEqualByComparingTo("26250"); // 150 * (300 - 125)

        // Deleting the second buy makes the sell impossible.
        assertThatThrownBy(() -> HoldingCalculator.calculate(List.of(sell, buy))).isInstanceOf(Oversell.class);
    }

    @Test
    void reopeningAfterAFullCloseStartsAFreshAverage() {
        Position position = single(
                trade(FPT, TransactionType.BUY, "2026-01-05", "10", "100", "0", "0"),
                trade(FPT, TransactionType.SELL, "2026-01-06", "10", "120", "0", "0"),
                trade(FPT, TransactionType.BUY, "2026-01-07", "10", "200", "0", "0"));

        assertThat(position.averageCost()).isEqualByComparingTo("200");
        assertThat(position.realizedPnl()).isEqualByComparingTo("200");
        assertThat(position.tradeCount()).isEqualTo(3);
    }

    @Test
    void instrumentsAreIndependentAndFractionalUnitsWork() {
        Map<UUID, Position> positions = HoldingCalculator.calculate(List.of(
                trade(VCB, TransactionType.BUY, "2026-01-05", "12.345678", "15000.5", "0", "0"),
                trade(FPT, TransactionType.BUY, "2026-01-05", "1", "1", "0", "0")));

        assertThat(positions).containsOnlyKeys(VCB, FPT);
        assertThat(positions.get(VCB).costBasis()).isEqualByComparingTo("185191.342839");
        assertThat(Money.quantity(positions.get(VCB).quantity()).toPlainString()).isEqualTo("12.345678");
    }

    @Test
    void displayRoundingIsHalfUpToCurrencyUnits() {
        assertThat(Money.amount(new BigDecimal("1234.5"), "VND").toPlainString()).isEqualTo("1235");
        assertThat(Money.amount(new BigDecimal("1234.565"), "USD").toPlainString()).isEqualTo("1234.57");
        assertThat(Money.percent(new BigDecimal("1"), new BigDecimal("3")).toPlainString()).isEqualTo("33.33");
        assertThat(Money.percent(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(Money.quantity(new BigDecimal("100.000000")).toPlainString()).isEqualTo("100");
    }

    private Position single(Trade... trades) {
        return HoldingCalculator.calculate(new ArrayList<>(List.of(trades))).get(trades[0].instrumentId());
    }

    private Trade trade(UUID instrument, TransactionType type, String date, String quantity, String price, String fees, String taxes) {
        sequence++;
        return new Trade(new UUID(0, sequence), instrument, instrument.equals(FPT) ? "FPT" : "VCB", type, LocalDate.parse(date),
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequence), new BigDecimal(quantity), new BigDecimal(price),
                new BigDecimal(fees), new BigDecimal(taxes));
    }
}

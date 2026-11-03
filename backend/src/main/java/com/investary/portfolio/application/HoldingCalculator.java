package com.investary.portfolio.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investary.portfolio.domain.TransactionType;

/**
 * Computes positions from transactions with the weighted-average cost method. Pure and deterministic:
 * the same transactions always give the same positions.
 * <ul>
 *   <li>Transactions are replayed per instrument in (trade date, creation time, id) order.</li>
 *   <li>BUY and OPENING_POSITION add {@code quantity} and {@code quantity × price + fees + taxes} to the
 *       cost basis, so purchase fees are part of the cost.</li>
 *   <li>SELL removes {@code costBasis × sold / held} (the whole cost basis when the position is closed)
 *       and realizes {@code sold × price − fees − taxes − removed cost}. Selling more than is held at that
 *       point in time fails with {@link Oversell}.</li>
 *   <li>Arithmetic uses {@link MathContext#DECIMAL128} and nothing is rounded here; rounding for display
 *       happens once, in {@link Money}.</li>
 * </ul>
 * Corporate actions (splits, bonus shares, rights issues, cash dividends) are not modelled.
 */
public final class HoldingCalculator {

    static final MathContext MC = MathContext.DECIMAL128;

    private static final Comparator<Trade> CHRONOLOGICAL = Comparator.comparing(Trade::tradeDate)
            .thenComparing(Trade::createdAt)
            .thenComparing(Trade::id);

    private HoldingCalculator() {
    }

    /**
     * @return positions by instrument in order of first trade, including closed positions (quantity 0)
     * @throws Oversell when a sell exceeds the quantity held on its trade date
     */
    public static Map<UUID, Position> calculate(List<Trade> trades) {
        Map<UUID, Position> positions = new LinkedHashMap<>();
        trades.stream().sorted(CHRONOLOGICAL).forEach(trade -> {
            Position position = positions.computeIfAbsent(trade.instrumentId(), Position::empty);
            positions.put(trade.instrumentId(), position.apply(trade));
        });
        return positions;
    }

    /**
     * One transaction as input to the calculation.
     *
     * @param symbol used only in error messages
     */
    public record Trade(UUID id, UUID instrumentId, String symbol, TransactionType type, LocalDate tradeDate, Instant createdAt,
                        BigDecimal quantity, BigDecimal price, BigDecimal fees, BigDecimal taxes) {
    }

    /**
     * Running position of one instrument.
     *
     * @param costBasis    remaining cost of the open quantity, including purchase fees and taxes
     * @param realizedPnl  sum of realized gains and losses of all sells, net of sell fees and taxes
     * @param boughtCost   total cost of all purchases (for information)
     * @param proceeds     total net sale proceeds (for information)
     */
    public record Position(UUID instrumentId, BigDecimal quantity, BigDecimal costBasis, BigDecimal realizedPnl,
                           BigDecimal boughtCost, BigDecimal proceeds, LocalDate firstTradeDate, LocalDate lastTradeDate,
                           int tradeCount) {

        static Position empty(UUID instrumentId) {
            return new Position(instrumentId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null, 0);
        }

        public boolean isOpen() {
            return quantity.signum() > 0;
        }

        /** Cost per unit of the open quantity, unrounded; {@code null} for a closed position. */
        public BigDecimal averageCost() {
            return isOpen() ? costBasis.divide(quantity, MC) : null;
        }

        Position apply(Trade trade) {
            LocalDate first = firstTradeDate == null ? trade.tradeDate() : firstTradeDate;
            BigDecimal gross = trade.quantity().multiply(trade.price(), MC);
            BigDecimal charges = trade.fees().add(trade.taxes(), MC);
            return switch (trade.type()) {
                case BUY, OPENING_POSITION -> {
                    BigDecimal cost = gross.add(charges, MC);
                    yield new Position(instrumentId, quantity.add(trade.quantity(), MC), costBasis.add(cost, MC), realizedPnl,
                            boughtCost.add(cost, MC), proceeds, first, trade.tradeDate(), tradeCount + 1);
                }
                case SELL -> {
                    int comparison = trade.quantity().compareTo(quantity);
                    if (comparison > 0) {
                        throw new Oversell(trade, quantity);
                    }
                    BigDecimal removed = comparison == 0 ? costBasis
                            : costBasis.multiply(trade.quantity(), MC).divide(quantity, MC);
                    BigDecimal net = gross.subtract(charges, MC);
                    BigDecimal remainingQuantity = quantity.subtract(trade.quantity(), MC);
                    BigDecimal remainingCost = comparison == 0 ? BigDecimal.ZERO : costBasis.subtract(removed, MC);
                    yield new Position(instrumentId, remainingQuantity, remainingCost, realizedPnl.add(net.subtract(removed, MC), MC),
                            boughtCost, proceeds.add(net, MC), first, trade.tradeDate(), tradeCount + 1);
                }
            };
        }
    }

    /**
     * A sell larger than the quantity held on its trade date.
     */
    public static final class Oversell extends RuntimeException {

        private final transient Trade trade;
        private final BigDecimal available;

        Oversell(Trade trade, BigDecimal available) {
            super("Sell of " + trade.quantity().stripTrailingZeros().toPlainString() + " " + trade.symbol() + " on "
                    + trade.tradeDate() + " exceeds the " + available.stripTrailingZeros().toPlainString() + " held at that date");
            this.trade = trade;
            this.available = available;
        }

        public Trade trade() {
            return trade;
        }

        public BigDecimal available() {
            return available;
        }
    }
}

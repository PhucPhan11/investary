package com.investary.portfolio.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Display rounding, applied once to computed values (never to stored transactions or intermediate
 * results). All rounding is HALF_UP:
 * <ul>
 *   <li>money amounts: the currency's minor unit (0 decimals for VND and other zero-decimal currencies, else 2);</li>
 *   <li>per-unit prices and average cost: 4 decimals;</li>
 *   <li>percentages: 2 decimals;</li>
 *   <li>quantities: as entered, without trailing zeros.</li>
 * </ul>
 */
public final class Money {

    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of("VND", "JPY", "KRW");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    public static BigDecimal amount(BigDecimal value, String currency) {
        if (value == null) {
            return null;
        }
        return value.setScale(ZERO_DECIMAL_CURRENCIES.contains(currency) ? 0 : 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal unitPrice(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal quantity(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** {@code part / total × 100}, or {@code null} when the total is zero or unknown. */
    public static BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED, HoldingCalculator.MC).divide(total, HoldingCalculator.MC).setScale(2, RoundingMode.HALF_UP);
    }
}

package com.investary.portfolio.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;
import com.investary.portfolio.domain.TransactionType;

/**
 * Inputs of the portfolio write services (validated in the API layer).
 */
public final class PortfolioCommands {

    private PortfolioCommands() {
    }

    /**
     * @param currency defaults to VND
     */
    public record InstrumentCommand(String symbol, String name, InstrumentType instrumentType, Exchange exchange, String currency,
                                    List<String> aliases) {
    }

    public record PortfolioCommand(String name, String description, String baseCurrency) {
    }

    /**
     * @param price for OPENING_POSITION, the cost per unit the position was acquired at
     */
    public record TransactionCommand(UUID instrumentId, TransactionType type, LocalDate tradeDate, BigDecimal quantity,
                                     BigDecimal price, BigDecimal fees, BigDecimal taxes, String notes) {
    }

    public record PriceCommand(BigDecimal price, LocalDate asOf, String source, String note) {
    }
}

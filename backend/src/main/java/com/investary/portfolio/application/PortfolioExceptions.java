package com.investary.portfolio.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/**
 * Expected failures of the portfolio module.
 */
public final class PortfolioExceptions {

    private PortfolioExceptions() {
    }

    public static class PortfolioNotFoundException extends ApiException {
        public PortfolioNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.PORTFOLIO_NOT_FOUND, "Portfolio not found", "Portfolio " + id + " was not found");
        }
    }

    public static class DuplicatePortfolioException extends ApiException {
        public DuplicatePortfolioException(String name) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_PORTFOLIO, "Duplicate portfolio",
                    "A portfolio named '" + name + "' already exists");
        }
    }

    public static class InstrumentNotFoundException extends ApiException {
        public InstrumentNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.INSTRUMENT_NOT_FOUND, "Instrument not found", "Instrument " + id + " was not found");
        }
    }

    public static class DuplicateInstrumentException extends ApiException {
        public DuplicateInstrumentException(String symbol) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_INSTRUMENT, "Duplicate instrument",
                    "An instrument with symbol " + symbol + " already exists");
        }
    }

    public static class InstrumentInUseException extends ApiException {
        public InstrumentInUseException(String symbol) {
            super(HttpStatus.CONFLICT, ErrorCode.INSTRUMENT_IN_USE, "Instrument in use",
                    "Instrument " + symbol + " has transactions; delete them first");
        }
    }

    public static class PriceNotFoundException extends ApiException {
        public PriceNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.PRICE_NOT_FOUND, "Price not found", "Price observation " + id + " was not found");
        }
    }

    public static class DuplicatePriceException extends ApiException {
        public DuplicatePriceException(String symbol, LocalDate asOf) {
            super(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_PRICE, "Duplicate price",
                    "A price for " + symbol + " on " + asOf + " from this source already exists");
        }
    }

    public static class TransactionNotFoundException extends ApiException {
        public TransactionNotFoundException(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found", "Transaction " + id + " was not found");
        }
    }

    public static class CurrencyMismatchException extends ApiException {
        public CurrencyMismatchException(String symbol, String instrumentCurrency, String portfolioCurrency) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.CURRENCY_MISMATCH, "Currency mismatch",
                    symbol + " is quoted in " + instrumentCurrency + " but the portfolio uses " + portfolioCurrency
                            + "; currency conversion is not supported");
        }
    }

    /**
     * A trade or price date after today in Asia/Ho_Chi_Minh. Reported like a field validation error.
     */
    public static class FutureDateException extends ApiException {

        private final String field;

        public FutureDateException(String field, LocalDate date) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Validation failed", field + " " + date + " is in the future");
            this.field = field;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("errors", Map.of(field, "must not be in the future"));
        }
    }

    /**
     * The change would make a sell larger than the quantity held on its trade date.
     */
    public static class InsufficientHoldingsException extends ApiException {

        private final Map<String, Object> properties;

        public InsufficientHoldingsException(HoldingCalculator.Oversell oversell) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.INSUFFICIENT_HOLDINGS, "Insufficient holdings", oversell.getMessage());
            HoldingCalculator.Trade trade = oversell.trade();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("symbol", trade.symbol());
            values.put("tradeDate", trade.tradeDate().toString());
            values.put("requested", Money.quantity(trade.quantity()));
            values.put("available", Money.quantity(oversell.available()));
            this.properties = Map.copyOf(values);
        }

        @Override
        public Map<String, Object> getProperties() {
            return properties;
        }
    }
}

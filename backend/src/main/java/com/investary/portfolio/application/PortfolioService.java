package com.investary.portfolio.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.portfolio.application.PortfolioCommands.PortfolioCommand;
import com.investary.portfolio.application.PortfolioCommands.TransactionCommand;
import com.investary.portfolio.application.PortfolioExceptions.CurrencyMismatchException;
import com.investary.portfolio.application.PortfolioExceptions.DuplicatePortfolioException;
import com.investary.portfolio.application.PortfolioExceptions.FutureDateException;
import com.investary.portfolio.application.PortfolioExceptions.InstrumentNotFoundException;
import com.investary.portfolio.application.PortfolioExceptions.InsufficientHoldingsException;
import com.investary.portfolio.application.PortfolioExceptions.PortfolioNotFoundException;
import com.investary.portfolio.application.PortfolioExceptions.TransactionNotFoundException;
import com.investary.portfolio.application.PortfolioViews.PortfolioView;
import com.investary.portfolio.application.PortfolioViews.TransactionView;
import com.investary.portfolio.domain.Instrument;
import com.investary.portfolio.domain.Portfolio;
import com.investary.portfolio.domain.PortfolioTransaction;
import com.investary.portfolio.persistence.InstrumentRepository;
import com.investary.portfolio.persistence.PortfolioRepository;
import com.investary.portfolio.persistence.PortfolioTransactionRepository;

/**
 * Creates and changes portfolios and their transactions. Every transaction change locks the portfolio,
 * applies the change, and replays the whole history; if any sell would then exceed the quantity held on
 * its date, the change is rolled back with {@code INSUFFICIENT_HOLDINGS}. Only the user, through the REST
 * API, calls this service.
 */
@Service
@Transactional
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final PortfolioRepository portfolios;
    private final PortfolioTransactionRepository transactions;
    private final InstrumentRepository instruments;
    private final Clock clock;

    public PortfolioService(PortfolioRepository portfolios, PortfolioTransactionRepository transactions,
                            InstrumentRepository instruments, Clock clock) {
        this.portfolios = portfolios;
        this.transactions = transactions;
        this.instruments = instruments;
        this.clock = clock;
    }

    public PortfolioView create(PortfolioCommand command) {
        String name = command.name().strip();
        if (portfolios.existsByNameIgnoreCase(name)) {
            throw new DuplicatePortfolioException(name);
        }
        String currency = command.baseCurrency() == null || command.baseCurrency().isBlank() ? "VND"
                : command.baseCurrency().strip().toUpperCase(Locale.ROOT);
        Portfolio portfolio = portfolios.saveAndFlush(new Portfolio(name, clean(command.description()), currency, clock.instant()));
        log.info("Portfolio created id={}", portfolio.getId());
        return PortfolioView.of(portfolio, 0);
    }

    /** Renames a portfolio. The base currency cannot change once transactions may exist. */
    public PortfolioView update(UUID id, PortfolioCommand command) {
        Portfolio portfolio = portfolios.findForUpdate(id).orElseThrow(() -> new PortfolioNotFoundException(id));
        String name = command.name().strip();
        if (portfolios.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new DuplicatePortfolioException(name);
        }
        portfolio.rename(name, clean(command.description()), clock.instant());
        return PortfolioView.of(portfolios.saveAndFlush(portfolio), transactions.countByPortfolioId(id));
    }

    public void delete(UUID id) {
        Portfolio portfolio = portfolios.findForUpdate(id).orElseThrow(() -> new PortfolioNotFoundException(id));
        portfolios.delete(portfolio);
        log.info("Portfolio deleted id={}", id);
    }

    public TransactionView addTransaction(UUID portfolioId, TransactionCommand command) {
        Portfolio portfolio = lock(portfolioId);
        Instrument instrument = instrument(command.instrumentId(), portfolio);
        PortfolioTransaction transaction = new PortfolioTransaction(portfolioId, clock.instant());
        apply(transaction, command);
        transaction = transactions.saveAndFlush(transaction);
        revalidate(portfolio);
        log.info("Transaction added portfolio={} transaction={} type={}", portfolioId, transaction.getId(), command.type());
        return PortfolioQueryService.view(transaction, instrument);
    }

    public TransactionView updateTransaction(UUID portfolioId, UUID transactionId, TransactionCommand command) {
        Portfolio portfolio = lock(portfolioId);
        Instrument instrument = instrument(command.instrumentId(), portfolio);
        PortfolioTransaction transaction = transactions.findByIdAndPortfolioId(transactionId, portfolioId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        apply(transaction, command);
        transactions.saveAndFlush(transaction);
        revalidate(portfolio);
        log.info("Transaction updated portfolio={} transaction={}", portfolioId, transactionId);
        return PortfolioQueryService.view(transaction, instrument);
    }

    public void deleteTransaction(UUID portfolioId, UUID transactionId) {
        Portfolio portfolio = lock(portfolioId);
        PortfolioTransaction transaction = transactions.findByIdAndPortfolioId(transactionId, portfolioId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        transactions.delete(transaction);
        transactions.flush();
        revalidate(portfolio);
        log.info("Transaction deleted portfolio={} transaction={}", portfolioId, transactionId);
    }

    private Portfolio lock(UUID portfolioId) {
        return portfolios.findForUpdate(portfolioId).orElseThrow(() -> new PortfolioNotFoundException(portfolioId));
    }

    private Instrument instrument(UUID instrumentId, Portfolio portfolio) {
        Instrument instrument = instruments.findById(instrumentId).orElseThrow(() -> new InstrumentNotFoundException(instrumentId));
        if (!instrument.getCurrency().equals(portfolio.getBaseCurrency())) {
            throw new CurrencyMismatchException(instrument.getSymbol(), instrument.getCurrency(), portfolio.getBaseCurrency());
        }
        return instrument;
    }

    private void apply(PortfolioTransaction transaction, TransactionCommand command) {
        if (command.tradeDate().isAfter(LocalDate.now(clock.withZone(PortfolioQueryService.MARKET_ZONE)))) {
            throw new FutureDateException("tradeDate", command.tradeDate());
        }
        transaction.update(command.instrumentId(), command.type(), command.tradeDate(), command.quantity(), command.price(),
                orZero(command.fees()), orZero(command.taxes()), clean(command.notes()), clock.instant());
    }

    /**
     * Replays the whole history after a change. Throwing rolls the surrounding transaction back, so an
     * invalid edit or delete leaves the stored history untouched.
     */
    private void revalidate(Portfolio portfolio) {
        List<PortfolioTransaction> history = transactions.findByPortfolioIdOrderByTradeDateAscCreatedAtAscIdAsc(portfolio.getId());
        Set<UUID> ids = new HashSet<>();
        history.forEach(t -> ids.add(t.getInstrumentId()));
        Map<UUID, Instrument> byId = instruments.findAllById(ids).stream().collect(Collectors.toMap(Instrument::getId, Function.identity()));
        try {
            HoldingCalculator.calculate(PortfolioQueryService.trades(history, byId));
        } catch (HoldingCalculator.Oversell e) {
            throw new InsufficientHoldingsException(e);
        }
        portfolio.touch(clock.instant());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

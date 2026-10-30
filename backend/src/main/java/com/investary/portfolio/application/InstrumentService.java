package com.investary.portfolio.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.portfolio.application.PortfolioCommands.InstrumentCommand;
import com.investary.portfolio.application.PortfolioCommands.PriceCommand;
import com.investary.portfolio.application.PortfolioExceptions.DuplicateInstrumentException;
import com.investary.portfolio.application.PortfolioExceptions.DuplicatePriceException;
import com.investary.portfolio.application.PortfolioExceptions.FutureDateException;
import com.investary.portfolio.application.PortfolioExceptions.InstrumentInUseException;
import com.investary.portfolio.application.PortfolioExceptions.InstrumentNotFoundException;
import com.investary.portfolio.application.PortfolioExceptions.PriceNotFoundException;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.domain.Instrument;
import com.investary.portfolio.domain.InstrumentPrice;
import com.investary.portfolio.persistence.InstrumentPriceRepository;
import com.investary.portfolio.persistence.InstrumentRepository;
import com.investary.portfolio.persistence.PortfolioTransactionRepository;

/**
 * Maintains instruments and their manual price observations. Only the user, through the REST API, calls
 * this service.
 */
@Service
@Transactional
public class InstrumentService {

    private final InstrumentRepository instruments;
    private final InstrumentPriceRepository prices;
    private final PortfolioTransactionRepository transactions;
    private final Clock clock;

    public InstrumentService(InstrumentRepository instruments, InstrumentPriceRepository prices,
                             PortfolioTransactionRepository transactions, Clock clock) {
        this.instruments = instruments;
        this.prices = prices;
        this.transactions = transactions;
        this.clock = clock;
    }

    public InstrumentView create(InstrumentCommand command) {
        String symbol = symbol(command.symbol());
        if (instruments.existsBySymbol(symbol)) {
            throw new DuplicateInstrumentException(symbol);
        }
        Instrument instrument = new Instrument(clock.instant());
        apply(instrument, symbol, command);
        return InstrumentView.of(instruments.saveAndFlush(instrument));
    }

    /** Updates an instrument. Its currency cannot change once it has transactions. */
    public InstrumentView update(UUID id, InstrumentCommand command) {
        Instrument instrument = instruments.findById(id).orElseThrow(() -> new InstrumentNotFoundException(id));
        String symbol = symbol(command.symbol());
        if (instruments.existsBySymbolAndIdNot(symbol, id)) {
            throw new DuplicateInstrumentException(symbol);
        }
        if (!currency(command.currency()).equals(instrument.getCurrency()) && transactions.existsByInstrumentId(id)) {
            throw new InstrumentInUseException(instrument.getSymbol());
        }
        apply(instrument, symbol, command);
        return InstrumentView.of(instruments.saveAndFlush(instrument));
    }

    public void delete(UUID id) {
        Instrument instrument = instruments.findById(id).orElseThrow(() -> new InstrumentNotFoundException(id));
        if (transactions.existsByInstrumentId(id)) {
            throw new InstrumentInUseException(instrument.getSymbol());
        }
        instruments.delete(instrument);
    }

    public PriceView addPrice(UUID instrumentId, PriceCommand command) {
        Instrument instrument = instruments.findById(instrumentId).orElseThrow(() -> new InstrumentNotFoundException(instrumentId));
        if (command.asOf().isAfter(LocalDate.now(clock.withZone(PortfolioQueryService.MARKET_ZONE)))) {
            throw new FutureDateException("asOf", command.asOf());
        }
        String source = command.source().strip();
        if (prices.existsByInstrumentIdAndAsOfAndSource(instrumentId, command.asOf(), source)) {
            throw new DuplicatePriceException(instrument.getSymbol(), command.asOf());
        }
        String note = command.note() == null || command.note().isBlank() ? null : command.note().strip();
        return PriceView.of(prices.saveAndFlush(new InstrumentPrice(instrumentId, command.price(), command.asOf(), source, note,
                clock.instant())));
    }

    public void deletePrice(UUID instrumentId, UUID priceId) {
        InstrumentPrice price = prices.findByIdAndInstrumentId(priceId, instrumentId)
                .orElseThrow(() -> new PriceNotFoundException(priceId));
        prices.delete(price);
    }

    private void apply(Instrument instrument, String symbol, InstrumentCommand command) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (command.aliases() != null) {
            command.aliases().stream().filter(Objects::nonNull).map(String::strip).filter(a -> !a.isEmpty()).forEach(aliases::add);
        }
        instrument.update(symbol, command.name().strip(), command.instrumentType(), command.exchange(), currency(command.currency()),
                List.copyOf(aliases), clock.instant());
    }

    private static String symbol(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static String currency(String value) {
        return value == null || value.isBlank() ? "VND" : value.strip().toUpperCase(Locale.ROOT);
    }
}

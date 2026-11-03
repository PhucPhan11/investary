package com.investary.portfolio.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.portfolio.application.HoldingCalculator.Position;
import com.investary.portfolio.application.HoldingCalculator.Trade;
import com.investary.portfolio.application.PortfolioExceptions.InstrumentNotFoundException;
import com.investary.portfolio.application.PortfolioExceptions.PortfolioNotFoundException;
import com.investary.portfolio.application.PortfolioValuator.Valuation;
import com.investary.portfolio.application.PortfolioViews.Allocation;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;
import com.investary.portfolio.application.PortfolioViews.PortfolioView;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.application.PortfolioViews.TransactionView;
import com.investary.portfolio.domain.Instrument;
import com.investary.portfolio.domain.InstrumentPrice;
import com.investary.portfolio.domain.Portfolio;
import com.investary.portfolio.domain.PortfolioTransaction;
import com.investary.portfolio.persistence.InstrumentPriceRepository;
import com.investary.portfolio.persistence.InstrumentRepository;
import com.investary.portfolio.persistence.PortfolioRepository;
import com.investary.portfolio.persistence.PortfolioTransactionRepository;

/**
 * Read-only portfolio queries: holdings, valuation, allocation and snapshots are computed on every call
 * from the stored transactions and price observations. This is the only portfolio service other modules
 * (portfolio intelligence) may use; it cannot change data.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioQueryService {

    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PortfolioRepository portfolios;
    private final PortfolioTransactionRepository transactions;
    private final InstrumentRepository instruments;
    private final InstrumentPriceRepository prices;
    private final PortfolioProperties properties;
    private final Clock clock;

    public PortfolioQueryService(PortfolioRepository portfolios, PortfolioTransactionRepository transactions,
                                 InstrumentRepository instruments, InstrumentPriceRepository prices,
                                 PortfolioProperties properties, Clock clock) {
        this.portfolios = portfolios;
        this.transactions = transactions;
        this.instruments = instruments;
        this.prices = prices;
        this.properties = properties;
        this.clock = clock;
    }

    public List<PortfolioView> portfolios() {
        return portfolios.findAllByOrderByNameAsc().stream()
                .map(portfolio -> PortfolioView.of(portfolio, transactions.countByPortfolioId(portfolio.getId())))
                .toList();
    }

    public PortfolioView portfolio(UUID id) {
        Portfolio portfolio = find(id);
        return PortfolioView.of(portfolio, transactions.countByPortfolioId(id));
    }

    public List<InstrumentView> instruments(String text) {
        List<Instrument> found = text == null || text.isBlank() ? instruments.findAllByOrderBySymbolAsc() : instruments.search(text.strip());
        return found.stream().map(InstrumentView::of).toList();
    }

    public InstrumentView instrument(UUID id) {
        return instruments.findById(id).map(InstrumentView::of).orElseThrow(() -> new InstrumentNotFoundException(id));
    }

    public List<PriceView> prices(UUID instrumentId) {
        instrument(instrumentId);
        return prices.findByInstrumentIdOrderByAsOfDescCreatedAtDesc(instrumentId).stream().map(PriceView::of).toList();
    }

    public Page<TransactionView> transactions(UUID portfolioId, Pageable pageable) {
        find(portfolioId);
        Page<PortfolioTransaction> page = transactions.findByPortfolioId(portfolioId, pageable);
        Map<UUID, Instrument> byId = instrumentsById(page.getContent());
        return page.map(transaction -> view(transaction, byId.get(transaction.getInstrumentId())));
    }

    public PortfolioSummary summary(UUID portfolioId) {
        return valuation(find(portfolioId)).summary();
    }

    public List<HoldingView> holdings(UUID portfolioId) {
        return valuation(find(portfolioId)).holdings();
    }

    public Allocation allocation(UUID portfolioId) {
        return valuation(find(portfolioId)).allocation();
    }

    /** Portfolio, summary and open holdings computed in one consistent read. */
    public PortfolioSnapshot snapshot(UUID portfolioId) {
        Portfolio portfolio = find(portfolioId);
        long count = transactions.countByPortfolioId(portfolioId);
        Valuation valuation = valuation(portfolio);
        Set<UUID> held = valuation.holdings().stream().map(HoldingView::instrumentId).collect(Collectors.toSet());
        List<InstrumentView> heldInstruments = instruments.findAllById(held).stream().map(InstrumentView::of).toList();
        return new PortfolioSnapshot(PortfolioView.of(portfolio, count), valuation.summary(), valuation.holdings(), heldInstruments);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(MARKET_ZONE));
    }

    private Valuation valuation(Portfolio portfolio) {
        List<PortfolioTransaction> history = transactions.findByPortfolioIdOrderByTradeDateAscCreatedAtAscIdAsc(portfolio.getId());
        Map<UUID, Instrument> byId = instrumentsById(history);
        Map<UUID, Position> positions;
        try {
            positions = HoldingCalculator.calculate(trades(history, byId));
        } catch (HoldingCalculator.Oversell e) {
            // Writes are validated, so this only happens if data was changed outside the API.
            throw new PortfolioExceptions.InsufficientHoldingsException(e);
        }
        LocalDate today = today();
        Map<UUID, PriceView> latest = new LinkedHashMap<>();
        if (!positions.isEmpty()) {
            for (InstrumentPrice price : prices.findObservations(positions.keySet(), today)) {
                latest.putIfAbsent(price.getInstrumentId(), PriceView.of(price));
            }
        }
        Map<UUID, InstrumentView> views = byId.values().stream().collect(Collectors.toMap(Instrument::getId, InstrumentView::of));
        return PortfolioValuator.value(portfolio.getId(), portfolio.getName(), portfolio.getBaseCurrency(), history.size(),
                positions, views, latest, today, properties.priceStaleAfterDays());
    }

    private Portfolio find(UUID id) {
        return portfolios.findById(id).orElseThrow(() -> new PortfolioNotFoundException(id));
    }

    private Map<UUID, Instrument> instrumentsById(List<PortfolioTransaction> list) {
        Set<UUID> ids = new HashSet<>();
        list.forEach(transaction -> ids.add(transaction.getInstrumentId()));
        return instruments.findAllById(ids).stream().collect(Collectors.toMap(Instrument::getId, Function.identity()));
    }

    static List<Trade> trades(List<PortfolioTransaction> history, Map<UUID, Instrument> instruments) {
        return history.stream()
                .map(t -> new Trade(t.getId(), t.getInstrumentId(), instruments.get(t.getInstrumentId()).getSymbol(), t.getType(),
                        t.getTradeDate(), t.getCreatedAt(), t.getQuantity(), t.getPrice(), t.getFees(), t.getTaxes()))
                .toList();
    }

    static TransactionView view(PortfolioTransaction t, Instrument instrument) {
        return new TransactionView(t.getId(), t.getPortfolioId(), t.getInstrumentId(), instrument.getSymbol(), instrument.getName(),
                t.getType(), t.getTradeDate(), Money.quantity(t.getQuantity()), Money.unitPrice(t.getPrice()), t.getFees(), t.getTaxes(),
                t.getQuantity().multiply(t.getPrice(), HoldingCalculator.MC), t.getNotes(), t.getCreatedAt(), t.getUpdatedAt());
    }
}

package com.investary.intelligence.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PortfolioView;

/**
 * Deterministic relevance of one new research source to the current portfolios, before any AI call:
 * a holding is relevant when one of the source's tickers equals its symbol or one of its aliases.
 * <p>
 * An ETF or fund matches only on its own symbol: its underlying holdings are never inferred, because the
 * application has no verified constituent data. Market commentary is only reported when the caller says
 * the source is market-wide and asks for it.
 */
@Service
public class SourceRelevanceService {

    private final PortfolioQueryService portfolios;

    public SourceRelevanceService(PortfolioQueryService portfolios) {
        this.portfolios = portfolios;
    }

    /**
     * @param tickers       tickers found in the source (any case)
     * @param marketContext true to also report every portfolio with open holdings as market context
     * @return relevant portfolios only, in portfolio order
     */
    public List<PortfolioRelevance> match(Collection<String> tickers, boolean marketContext) {
        Set<String> wanted = tickers.stream().filter(t -> t != null && !t.isBlank()).map(SourceRelevanceService::normalize)
                .collect(Collectors.toSet());
        List<PortfolioRelevance> result = new ArrayList<>();
        for (PortfolioView portfolio : portfolios.portfolios()) {
            PortfolioSnapshot snapshot = portfolios.snapshot(portfolio.id());
            Map<UUID, InstrumentView> instruments = snapshot.instruments().stream()
                    .collect(Collectors.toMap(InstrumentView::id, Function.identity(), (a, b) -> a));
            List<HoldingView> open = snapshot.holdings().stream().filter(h -> h.quantity() != null && h.quantity().signum() > 0).toList();
            List<RelevantHolding> holdings = open.stream()
                    .filter(h -> matches(h, instruments.get(h.instrumentId()), wanted))
                    .map(h -> new RelevantHolding(h.instrumentId(), h.symbol(), h.instrumentType().name()))
                    .toList();
            boolean market = marketContext && !open.isEmpty();
            if (!holdings.isEmpty() || market) {
                result.add(new PortfolioRelevance(portfolio.id(), ImpactAssessmentService.snapshotHash(snapshot), holdings, market));
            }
        }
        return result;
    }

    private static boolean matches(HoldingView holding, InstrumentView instrument, Set<String> wanted) {
        if (wanted.contains(normalize(holding.symbol()))) {
            return true;
        }
        return instrument != null && instrument.aliases() != null
                && instrument.aliases().stream().filter(a -> a != null && !a.isBlank()).map(SourceRelevanceService::normalize)
                .anyMatch(wanted::contains);
    }

    private static String normalize(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * @param snapshotHash hash of the holdings used, the same as on impact assessments
     */
    public record PortfolioRelevance(UUID portfolioId, String snapshotHash, List<RelevantHolding> holdings, boolean marketContext) {
    }

    public record RelevantHolding(UUID instrumentId, String symbol, String instrumentType) {
    }
}

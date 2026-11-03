package com.investary.intelligence.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.research.link.Keywords;

/**
 * Deterministic portfolio-aware retrieval on top of the Phase 3 {@link HybridSearchService}; there is no
 * second index or ranking. For each holding it searches by ticker (chunks that name the ticker) and by
 * name (symbol, name and aliases, kept only when the text really contains one of them), then classifies
 * every hit:
 * <ul>
 *   <li>{@code DIRECT}: the document's ticker is the holding, its title names it, or the holding is the only
 *       ticker the chunk names;</li>
 *   <li>{@code MENTION}: the holding is one of several tickers, or only its name appears;</li>
 *   <li>{@code MARKET_CONTEXT}: ticker-less market commentary from a separate market query.</li>
 * </ul>
 * Freshness uses the source date (publication date, or the email sent date when the publication date is
 * unknown); the import date is never used.
 */
@Service
public class ResearchMatcher {

    /** Lexical/semantic probe for general market commentary. */
    static final String MARKET_QUERY = "Thị trường chứng khoán VN-Index thanh khoản lãi suất tỷ giá vĩ mô";

    private static final Comparator<MatchedEvidence> ORDER = Comparator
            .comparing((MatchedEvidence evidence) -> evidence.hit().publishedOn(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(evidence -> -evidence.hit().score());

    private final HybridSearchService search;
    private final IntelligenceProperties properties;

    public ResearchMatcher(HybridSearchService search, IntelligenceProperties properties) {
        this.search = search;
        this.properties = properties;
    }

    public enum MatchKind {
        DIRECT,
        MENTION,
        MARKET_CONTEXT
    }

    /**
     * @param ageDays  days between the source date and today; {@code null} when the source has no date
     * @param outdated older than {@code investary.intelligence.research-fresh-days}, or undated
     */
    public record MatchedEvidence(SearchHit hit, MatchKind kind, Long ageDays, boolean outdated) {
    }

    public record HoldingResearch(UUID instrumentId, String symbol, String name, List<MatchedEvidence> direct,
                                  List<MatchedEvidence> mentions, LocalDate newestEvidenceDate) {

        public boolean hasEvidence() {
            return !direct.isEmpty() || !mentions.isEmpty();
        }

        public List<MatchedEvidence> all() {
            return Stream.concat(direct.stream(), mentions.stream()).toList();
        }
    }

    /**
     * @param mode LEXICAL_ONLY when no embedding model is configured
     */
    public record PortfolioResearch(List<HoldingResearch> holdings, List<MatchedEvidence> marketContext, SearchResult.Mode mode,
                                    LocalDate today, int freshDays) {
    }

    public record ResearchFilters(String brokerage, LocalDate from, LocalDate to) {

        public static final ResearchFilters NONE = new ResearchFilters(null, null, null);

        SearchFilters withTicker(String ticker) {
            return new SearchFilters(ticker, brokerage, null, from, to);
        }
    }

    public PortfolioResearch match(List<InstrumentView> instruments, ResearchFilters filters, LocalDate today) {
        int perKind = properties.evidencePerHolding();
        SearchResult.Mode mode = SearchResult.Mode.HYBRID;
        List<HoldingResearch> holdings = new ArrayList<>();
        for (InstrumentView instrument : instruments) {
            Map<UUID, SearchHit> hits = new LinkedHashMap<>();
            if (looksLikeTicker(instrument.symbol())) {
                SearchResult byTicker = search.search(instrument.symbol() + " " + instrument.name(),
                        filters.withTicker(instrument.symbol()), perKind * 2);
                mode = byTicker.mode();
                byTicker.hits().forEach(hit -> hits.putIfAbsent(hit.chunkId(), hit));
            }
            List<String> names = names(instrument);
            SearchResult byName = search.search(String.join(" ", names) + " " + instrument.symbol(), filters.withTicker(null), perKind * 2);
            mode = byName.mode();
            byName.hits().stream()
                    .filter(hit -> mentions(hit, instrument.symbol(), names))
                    .forEach(hit -> hits.putIfAbsent(hit.chunkId(), hit));

            List<MatchedEvidence> direct = new ArrayList<>();
            List<MatchedEvidence> passing = new ArrayList<>();
            for (SearchHit hit : hits.values()) {
                MatchKind kind = classify(hit, instrument.symbol(), names);
                (kind == MatchKind.DIRECT ? direct : passing).add(evidence(hit, kind, today));
            }
            direct.sort(ORDER);
            passing.sort(ORDER);
            List<MatchedEvidence> keptDirect = direct.stream().limit(perKind).toList();
            List<MatchedEvidence> keptMentions = passing.stream().limit(perKind).toList();
            LocalDate newest = Stream.concat(keptDirect.stream(), keptMentions.stream())
                    .map(evidence -> evidence.hit().publishedOn()).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            holdings.add(new HoldingResearch(instrument.id(), instrument.symbol(), instrument.name(), keptDirect, keptMentions, newest));
        }

        SearchResult market = search.search(MARKET_QUERY, filters.withTicker(null), properties.marketContextResults() * 2);
        List<MatchedEvidence> context = market.hits().stream()
                .filter(hit -> hit.tickers().isEmpty() && hit.sourceTicker() == null)
                .map(hit -> evidence(hit, MatchKind.MARKET_CONTEXT, today))
                .sorted(ORDER)
                .limit(properties.marketContextResults())
                .toList();
        return new PortfolioResearch(holdings, context, instruments.isEmpty() ? market.mode() : mode, today,
                properties.researchFreshDays());
    }

    static MatchKind classify(SearchHit hit, String symbol, List<String> names) {
        if (symbol.equalsIgnoreCase(hit.sourceTicker())) {
            return MatchKind.DIRECT;
        }
        if (namesSymbol(hit.title(), symbol) || containsAnyName(hit.title(), names)) {
            return MatchKind.DIRECT;
        }
        if (hit.tickers().size() == 1 && hit.tickers().getFirst().equalsIgnoreCase(symbol)) {
            return MatchKind.DIRECT;
        }
        return MatchKind.MENTION;
    }

    /** True when the chunk (or its title) really contains the symbol or one of the instrument's names. */
    static boolean mentions(SearchHit hit, String symbol, List<String> names) {
        if (hit.tickers().stream().anyMatch(symbol::equalsIgnoreCase) || symbol.equalsIgnoreCase(hit.sourceTicker())) {
            return true;
        }
        String text = hit.title() + "\n" + hit.content();
        return namesSymbol(text, symbol) || containsAnyName(text, names);
    }

    private MatchedEvidence evidence(SearchHit hit, MatchKind kind, LocalDate today) {
        Long age = hit.publishedOn() == null ? null : ChronoUnit.DAYS.between(hit.publishedOn(), today);
        boolean outdated = age == null || age > properties.researchFreshDays();
        return new MatchedEvidence(hit, kind, age, outdated);
    }

    /** Instrument name and aliases with at least four characters (short names match too much text). */
    static List<String> names(InstrumentView instrument) {
        List<String> names = new ArrayList<>();
        Stream.concat(Stream.of(instrument.name()), instrument.aliases().stream())
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(name -> name.length() >= 4)
                .distinct()
                .forEach(names::add);
        return names;
    }

    private static boolean namesSymbol(String text, String symbol) {
        if (text == null) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(symbol.toUpperCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static boolean containsAnyName(String text, List<String> names) {
        if (text == null) {
            return false;
        }
        // Folded text keeps punctuation, so match at a word start ("cong ty fpt," still matches "cong ty fpt").
        String folded = " " + Keywords.normalize(text);
        return names.stream().map(Keywords::normalize).filter(name -> !name.isBlank())
                .anyMatch(name -> folded.contains(" " + name));
    }

    private static boolean looksLikeTicker(String symbol) {
        return symbol.matches("^[A-Za-z0-9]{2,10}$");
    }
}

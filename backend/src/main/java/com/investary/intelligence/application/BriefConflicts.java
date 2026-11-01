package com.investary.intelligence.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.investary.intelligence.application.BriefEvidenceCollector.Entry;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.research.link.Keywords;

/**
 * Conservative, deterministic detection of differing brokerage views: an explicit rating label
 * ("Khuyến nghị: MUA", "Rating: Outperform") in a source about one ticker. Two brokerages covering the same
 * ticker with ratings of different stance are reported as a conflict; both sides are shown with their
 * citations and Investary never picks one. Differently worded opinions are not detected here.
 */
final class BriefConflicts {

    /** Longer terms first, so "kem kha quan" wins over "kha quan" and "cho mua" over "mua". */
    private static final Pattern RATING = Pattern.compile("\\b(?:khuyen nghi|recommendation|rating)[^\\p{L}\\p{N}]+("
            + "kem kha quan|kha quan|trung lap|nam giu|cho mua|theo doi|mua|ban|outperform|underperform|neutral|accumulate|reduce|"
            + "hold|buy|sell|add)\\b");

    private static final Map<String, Rating> TERMS = Map.ofEntries(
            Map.entry("kem kha quan", new Rating("Kém khả quan", Stance.NEGATIVE)),
            Map.entry("kha quan", new Rating("Khả quan", Stance.POSITIVE)),
            Map.entry("trung lap", new Rating("Trung lập", Stance.NEUTRAL)),
            Map.entry("nam giu", new Rating("Nắm giữ", Stance.NEUTRAL)),
            Map.entry("cho mua", new Rating("Chờ mua", Stance.NEUTRAL)),
            Map.entry("theo doi", new Rating("Theo dõi", Stance.NEUTRAL)),
            Map.entry("mua", new Rating("Mua", Stance.POSITIVE)),
            Map.entry("ban", new Rating("Bán", Stance.NEGATIVE)),
            Map.entry("outperform", new Rating("Outperform", Stance.POSITIVE)),
            Map.entry("underperform", new Rating("Underperform", Stance.NEGATIVE)),
            Map.entry("neutral", new Rating("Neutral", Stance.NEUTRAL)),
            Map.entry("accumulate", new Rating("Accumulate", Stance.POSITIVE)),
            Map.entry("reduce", new Rating("Reduce", Stance.NEGATIVE)),
            Map.entry("hold", new Rating("Hold", Stance.NEUTRAL)),
            Map.entry("buy", new Rating("Buy", Stance.POSITIVE)),
            Map.entry("sell", new Rating("Sell", Stance.NEGATIVE)),
            Map.entry("add", new Rating("Add", Stance.POSITIVE)));

    private BriefConflicts() {
    }

    enum Stance {
        POSITIVE,
        NEUTRAL,
        NEGATIVE
    }

    record Rating(String label, Stance stance) {
    }

    /**
     * @param label citation label of the chunk the rating was read from
     */
    record View(String ticker, String brokerage, Rating rating, String label, LocalDate publishedOn) {
    }

    /** Ticker → views of different brokerages with more than one stance. */
    static Map<String, List<View>> detect(List<Entry> entries) {
        Map<String, Map<String, View>> byTicker = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String ticker = ownTicker(entry);
            if (ticker == null || entry.source().brokerage() == null) {
                continue;
            }
            for (LabeledHit hit : entry.hits()) {
                Optional<Rating> rating = rating(hit.hit().content());
                if (rating.isPresent()) {
                    byTicker.computeIfAbsent(ticker, t -> new LinkedHashMap<>())
                            .putIfAbsent(entry.source().brokerage().strip().toUpperCase(Locale.ROOT),
                                    new View(ticker, entry.source().brokerage(), rating.get(), hit.label(), entry.source().publishedOn()));
                    break;
                }
            }
        }
        Map<String, List<View>> conflicts = new LinkedHashMap<>();
        byTicker.forEach((ticker, views) -> {
            if (views.size() > 1 && views.values().stream().map(v -> v.rating().stance()).distinct().count() > 1) {
                conflicts.put(ticker, new ArrayList<>(views.values()));
            }
        });
        return conflicts;
    }

    static Optional<Rating> rating(String text) {
        Matcher matcher = RATING.matcher(Keywords.normalize(text));
        return matcher.find() ? Optional.ofNullable(TERMS.get(matcher.group(1))) : Optional.empty();
    }

    /** The one ticker a source is about: its metadata ticker, or the only ticker it names. */
    private static String ownTicker(Entry entry) {
        if (entry.source().sourceTicker() != null && !entry.source().sourceTicker().isBlank()) {
            return entry.source().sourceTicker().strip().toUpperCase(Locale.ROOT);
        }
        return entry.source().tickers().size() == 1 ? entry.source().tickers().getFirst().toUpperCase(Locale.ROOT) : null;
    }
}

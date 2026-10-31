package com.investary.research.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.investary.research.application.ResearchSource;
import com.investary.research.link.Keywords;

/**
 * Deterministic extraction of research items from section-structured email content.
 * <p>
 * Sections are recognised by bilingual heading keywords; each body block of a recognised section
 * becomes one item that keeps the original text. Stock recommendations are only created when the
 * source states one explicitly as {@code TICKER - <rating> - text} with a known rating phrase, and
 * the rating is stored exactly as written (e.g. "Chờ mua"). Vietnamese and English copies of the
 * same recommendation are merged into one item. No figures are parsed, computed or inferred.
 */
@Component
public class ResearchItemExtractor {

    public static final String METHOD = "RULES_V1";

    private static final int MIN_CONTENT_LENGTH = 20;
    private static final int MAX_LOCATION_LENGTH = 500;

    private static final List<String> PROMOTION = List.of("uu dai", "khuyen mai", "promotion", "offers", "offer");
    private static final List<String> RECOMMENDATION = List.of(
            "khuyen nghi", "recommendation", "recommendations", "co phieu khuyen nghi", "stock pick", "stock picks", "goi y dau tu");
    private static final List<String> COMPANY = List.of(
            "tin doanh nghiep", "corporate news", "company news", "tin cong ty", "doanh nghiep", "corporate");
    private static final List<String> INDUSTRY = List.of("nganh", "sector", "sectors", "industry", "industries");
    private static final List<String> MACRO = List.of("vi mo", "macro", "macroeconomics", "macroeconomic", "kinh te");
    private static final List<String> MARKET = List.of(
            "thi truong chung", "general market", "nhan dinh thi truong", "market update", "market news", "thi truong", "market");

    private static final Set<String> RATINGS = Set.of(
            "mua", "cho mua", "mua manh", "mua them", "tich luy", "nam giu", "ban", "ban manh", "kha quan", "trung lap",
            "kem kha quan", "theo doi", "tang ty trong", "giam ty trong", "buy", "strong buy", "awaiting buy", "trading buy",
            "accumulate", "add", "hold", "neutral", "sell", "reduce", "outperform", "underperform", "market perform",
            "overweight", "underweight");

    private static final Pattern RECOMMENDATION_PATTERN =
            Pattern.compile("^([A-Z][A-Z0-9]{2})\\s*[-–:]\\s*([^-–:\\n]{2,40}?)\\s*[-–:]\\s+(\\S.*)$", Pattern.DOTALL);
    private static final Pattern TICKER_LEAD = Pattern.compile("^([A-Z][A-Z0-9]{2})\\s*:?$");

    public List<ExtractedItem> extract(ResearchSource source) {
        List<ExtractedItem> items = new ArrayList<>();
        Map<String, ExtractedItem> recommendationsByTicker = new LinkedHashMap<>();

        for (ResearchSource.Block block : source.blocks()) {
            if (block.headingLevel() > 0 || block.text().length() < MIN_CONTENT_LENGTH) {
                continue;
            }
            Optional<ResearchItemType> type = sectionType(block.section());
            if (type.isEmpty()) {
                continue;
            }
            String location = location(block);
            switch (type.get()) {
                case STOCK_RECOMMENDATION -> recommendation(block, location).ifPresent(item -> {
                    ExtractedItem existing = recommendationsByTicker.get(item.ticker());
                    if (existing == null) {
                        recommendationsByTicker.put(item.ticker(), item);
                        items.add(item);
                    } else {
                        // The same recommendation in another language is a translation, not a second recommendation.
                        existing.addTranslation(item);
                    }
                });
                case COMPANY_NEWS -> {
                    String ticker = block.lead() == null ? null : tickerFromLead(block.lead());
                    items.add(item(ResearchItemType.COMPANY_NEWS, ticker, block, location));
                }
                default -> items.add(item(type.get(), null, block, location));
            }
        }
        return items;
    }

    /**
     * The most specific heading decides: the sub-heading first, then the top-level heading.
     */
    static Optional<ResearchItemType> sectionType(String section) {
        if (section == null) {
            return Optional.empty();
        }
        String[] parts = section.split(" > ");
        for (int i = parts.length - 1; i >= 0; i--) {
            String heading = Keywords.normalize(parts[i].replaceFirst("^(\\d{1,2}|[IVX]{1,4})[.)]\\s*", ""));
            if (Keywords.containsAny(heading, PROMOTION)) {
                return Optional.empty();
            }
            if (Keywords.containsAny(heading, RECOMMENDATION)) {
                return Optional.of(ResearchItemType.STOCK_RECOMMENDATION);
            }
            if (Keywords.containsAny(heading, COMPANY)) {
                return Optional.of(ResearchItemType.COMPANY_NEWS);
            }
            if (Keywords.containsAny(heading, INDUSTRY)) {
                return Optional.of(ResearchItemType.INDUSTRY_UPDATE);
            }
            if (Keywords.containsAny(heading, MACRO)) {
                return Optional.of(ResearchItemType.MACRO_UPDATE);
            }
            if (Keywords.containsAny(heading, MARKET)) {
                return Optional.of(ResearchItemType.MARKET_UPDATE);
            }
        }
        return Optional.empty();
    }

    private static Optional<ExtractedItem> recommendation(ResearchSource.Block block, String location) {
        Matcher matcher = RECOMMENDATION_PATTERN.matcher(block.text());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String ticker = matcher.group(1);
        String rating = matcher.group(2).strip();
        if (!RATINGS.contains(Keywords.normalize(rating))) {
            return Optional.empty();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticker", ticker);
        data.put("sourceRecommendation", rating);
        data.put("note", "Recommendation as stated by the source brokerage; not an Investary recommendation.");
        return Optional.of(new ExtractedItem(ResearchItemType.STOCK_RECOMMENDATION, ticker, ticker + " - " + rating,
                block.text(), data, location, block.language()));
    }

    private static ExtractedItem item(ResearchItemType type, String ticker, ResearchSource.Block block, String location) {
        Map<String, Object> data = new LinkedHashMap<>();
        String title = null;
        if (block.lead() != null) {
            title = block.lead().replaceFirst("\\s*:$", "");
            data.put("label", title);
        }
        if (ticker != null) {
            data.put("ticker", ticker);
        }
        return new ExtractedItem(type, ticker, title, block.text(), data, location, block.language());
    }

    private static String tickerFromLead(String lead) {
        Matcher matcher = TICKER_LEAD.matcher(lead.strip());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Zero-padded block index first so locations sort in reading order. */
    private static String location(ResearchSource.Block block) {
        String value = "block %04d | %s".formatted(block.index(), block.section());
        return value.length() > MAX_LOCATION_LENGTH ? value.substring(0, MAX_LOCATION_LENGTH) : value;
    }

    /**
     * @param data values copied from the source text; serialized as the item's structured data
     */
    public record ExtractedItem(ResearchItemType type, String ticker, String title, String content,
                                Map<String, Object> data, String sourceLocation, String language) {

        @SuppressWarnings("unchecked")
        void addTranslation(ExtractedItem translation) {
            if (translation.language() == null || translation.language().equals(language)) {
                return;
            }
            Map<String, Object> translations = (Map<String, Object>) data.computeIfAbsent("translations", k -> new LinkedHashMap<String, Object>());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceRecommendation", translation.data().get("sourceRecommendation"));
            entry.put("content", translation.content());
            entry.put("sourceLocation", translation.sourceLocation());
            translations.put(translation.language(), entry);
        }
    }
}

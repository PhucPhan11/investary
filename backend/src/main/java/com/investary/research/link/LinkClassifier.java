package com.investary.research.link;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.investary.common.web.UrlCodec;

/**
 * Deterministic, rule-based link classification. Rules are evaluated in order and the first match
 * wins; the rule name is stored with the link so every category can be explained. The rules are
 * generic (URL path, file extension, link text, enclosing section) and work across brokerages;
 * anything that does not match confidently is {@link LinkCategory#OTHER}.
 */
@Component
public class LinkClassifier {

    private static final List<String> SUBSCRIPTION = List.of(
            "unsubscribe", "huy nhan", "huy dang ky", "dang ky nhan", "quan ly lua chon", "manage preferences",
            "subscribe", "view in browser", "xem tren trinh duyet", "email preferences");
    private static final Set<String> SOCIAL_DOMAINS = Set.of(
            "facebook.com", "youtube.com", "youtu.be", "linkedin.com", "twitter.com", "x.com", "zalo.me",
            "tiktok.com", "instagram.com", "t.me", "telegram.me");
    private static final List<String> ACCOUNT_PATHS = List.of(
            "/login", "/signin", "/sign-in", "/register", "/signup", "/app/launch", "/guest/", "/account");
    private static final List<String> PROMOTION_SECTIONS = List.of("uu dai", "khuyen mai", "promotion", "offers", "offer");
    private static final List<String> PROMOTION_PATHS = List.of("/uu-dai", "/khuyen-mai", "/promotion", "/promo", "/offer", "/campaign");
    private static final List<String> PROMOTION_TEXT = List.of(
            "uu dai", "khuyen mai", "hoan phi", "mien phi", "voucher", "qua tang", "promotion", "cashback", "discount");
    private static final List<String> LISTING_TEXT = List.of("xem toan bo", "xem them", "view all", "see all", "more reports");
    private static final List<String> LISTING_PATHS = List.of("bao-cao-phan-tich", "analyst-reports", "analysis-reports");
    private static final List<String> MARKET_TERMS = List.of(
            "thi truong", "trai phieu", "vi mo", "chien luoc", "kinh te", "trien vong", "ty gia", "lai suat",
            "bond", "market", "macro", "strategy", "outlook", "economy", "interest rate");
    private static final List<String> REPORT_TERMS = List.of(
            "bao cao", "phan tich", "cap nhat kqkd", "kqkd", "report", "research", "earnings update", "results update");

    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z0-9]{2}");
    private static final Pattern TICKER_AT_TEXT_START = Pattern.compile("^([A-Z][A-Z0-9]{2})(?:\\s*[-–:|]|$)");
    private static final Set<String> NOT_TICKERS = Set.of(
            "VND", "USD", "EUR", "JPY", "CNY", "ETF", "GDP", "CPI", "PMI", "FDI", "IPO", "EPS", "ROE", "ROA", "NAV",
            "PDF", "THE", "AND", "FOR", "NEW", "ALL", "TOP", "KEY", "HOT", "ESG", "MUA", "BAN", "CEO", "CFO", "NIM");

    public Classification classify(String targetUrl, String host, String displayText, String section) {
        URI uri = parse(targetUrl);
        String rawPath = uri == null || uri.getRawPath() == null ? "" : uri.getRawPath();
        String path = UrlCodec.percentDecode(rawPath).toLowerCase(Locale.ROOT);
        String fileName = fileName(UrlCodec.percentDecode(rawPath));
        boolean pdf = path.endsWith(".pdf");
        String text = Keywords.normalize(displayText);
        String sectionText = Keywords.normalize(section);
        String pathWords = Keywords.normalize(path);

        if (Keywords.containsAny(text, SUBSCRIPTION) || Keywords.containsAny(pathWords, SUBSCRIPTION)) {
            return new Classification(LinkCategory.NAVIGATION, "navigation.subscription", null, pdf);
        }
        if (isSocial(host)) {
            return new Classification(LinkCategory.NAVIGATION, "navigation.social", null, pdf);
        }
        if (ACCOUNT_PATHS.stream().anyMatch(path::contains)) {
            return new Classification(LinkCategory.NAVIGATION, "navigation.account", null, pdf);
        }
        if (Keywords.containsAny(sectionText, PROMOTION_SECTIONS)) {
            return new Classification(LinkCategory.PROMOTION, "promotion.section", null, pdf);
        }
        if (PROMOTION_PATHS.stream().anyMatch(path::contains) || Keywords.containsAny(text, PROMOTION_TEXT)) {
            return new Classification(LinkCategory.PROMOTION, "promotion.keywords", null, pdf);
        }
        if (!pdf) {
            if (Keywords.containsAny(text, LISTING_TEXT) || LISTING_PATHS.stream().anyMatch(path::contains)) {
                return new Classification(LinkCategory.NAVIGATION, "navigation.report-listing", null, false);
            }
            if (Keywords.containsAny(text, REPORT_TERMS)) {
                return new Classification(LinkCategory.RESEARCH_REPORT, "research.keywords", ticker(displayText, fileName).orElse(null), false);
            }
            return new Classification(LinkCategory.OTHER, "default", null, false);
        }

        Optional<String> ticker = ticker(displayText, fileName);
        if (ticker.isPresent()) {
            return new Classification(LinkCategory.STOCK_RESEARCH, "stock.ticker", ticker.get(), true);
        }
        if (Keywords.containsAny(text + " " + Keywords.normalize(fileName), MARKET_TERMS)) {
            return new Classification(LinkCategory.MARKET_REPORT, "market.keywords", null, true);
        }
        return new Classification(LinkCategory.RESEARCH_REPORT, "research.pdf", null, true);
    }

    /**
     * Finds a ticker at the start of the link text ({@code "IMP - Q3 update"}, {@code "VIC"}) or as a
     * separate upper-case token in the file name ({@code 20261028_IMP_..., VIC_EN.pdf}).
     */
    static Optional<String> ticker(String displayText, String fileName) {
        if (displayText != null) {
            Matcher matcher = TICKER_AT_TEXT_START.matcher(displayText.strip());
            if (matcher.find() && !NOT_TICKERS.contains(matcher.group(1))) {
                return Optional.of(matcher.group(1));
            }
        }
        if (fileName != null) {
            String base = fileName.replaceFirst("(?i)\\.pdf$", "");
            for (String token : base.split("[_\\-.\\s]+")) {
                if (TICKER.matcher(token).matches() && !NOT_TICKERS.contains(token)) {
                    return Optional.of(token);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isSocial(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return SOCIAL_DOMAINS.stream().anyMatch(domain -> h.equals(domain) || h.endsWith("." + domain));
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? null : name;
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

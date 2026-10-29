package com.investary.knowledge.chunking;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Vietnamese stock tickers (three upper-case ASCII letters or digits, starting with a letter)
 * that a piece of text names explicitly. It only says which tickers a chunk <i>mentions</i>; it never
 * decides that a whole document is "about" one ticker.
 * <p>
 * Common upper-case words that look like tickers (currencies, indicators, ratings, words in
 * upper-case Vietnamese headings) are excluded. Lines written mostly in capitals are ignored, because
 * headings such as "TIN TỨC THỊ TRƯỜNG" would otherwise produce false tickers.
 */
public final class TickerDetector {

    private static final Pattern TICKER = Pattern.compile("(?<![\\p{L}\\p{N}])[A-Z][A-Z0-9]{2}(?![\\p{L}\\p{N}])");
    private static final Set<String> NOT_TICKERS = Set.of(
            // currencies, indicators, institutions
            "USD", "VND", "EUR", "JPY", "CNY", "GBP", "KRW", "GDP", "CPI", "PPI", "PMI", "FDI", "FII", "ETF", "IPO",
            "ROE", "ROA", "EPS", "NIM", "NPL", "LDR", "CAR", "EBT", "FED", "ECB", "BOJ", "PBOC", "IMF", "WTO", "OMO",
            "WTI", "LNG", "VAT", "SME", "ESG", "CEO", "CFO", "CTO", "AGM", "YOY", "QOQ", "YTD", "TTM", "MOM", "ATH",
            "HNX", "HSX", "VNX", "SBV", "MOF", "NHNN", "OCR", "PDF", "URL", "API", "FAQ", "USA", "ASEAN",
            // ratings and common words
            "BUY", "MUA", "BAN", "GIU", "TOP", "NEW", "KEY", "ALL", "AND", "FOR", "THE", "NOT", "ARE", "YES",
            // unaccented Vietnamese words that appear in capitals
            "TIN", "CHO", "CAO", "HAI", "BAO", "CAC", "VAN", "NAM", "MOI", "VON", "GIA", "SAN", "DAU");
    private static final double HEADING_UPPERCASE_RATIO = 0.6;
    private static final int HEADING_MIN_LETTERS = 12;

    private TickerDetector() {
    }

    public static Set<String> detect(String text) {
        Set<String> tickers = new LinkedHashSet<>();
        if (text == null) {
            return tickers;
        }
        for (String line : text.split("\\R")) {
            if (isUpperCaseHeading(line)) {
                continue;
            }
            Matcher matcher = TICKER.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group();
                if (!NOT_TICKERS.contains(candidate)) {
                    tickers.add(candidate);
                }
            }
        }
        return tickers;
    }

    static boolean isUpperCaseHeading(String line) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters >= HEADING_MIN_LETTERS && (double) upper / letters >= HEADING_UPPERCASE_RATIO;
    }
}

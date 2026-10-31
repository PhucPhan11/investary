package com.investary.knowledge.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.investary.knowledge.chunking.TickerDetector;
import com.investary.research.link.Keywords;

/**
 * What a question names explicitly: tickers, brokerages and the content words used for lexical search.
 * A known brokerage name (e.g. SSI) is treated as the brokerage, not as a ticker.
 *
 * @param terms accent-folded content words (stop words removed), used for lexical matching
 */
public record QueryAnalysis(List<String> tickers, List<String> brokerages, List<String> terms) {

    private static final Set<String> STOP_WORDS = Set.copyOf(List.of(
            // Vietnamese (accent-folded)
            "la", "gi", "cua", "va", "cac", "nhung", "nhu", "the", "nao", "ve", "cho", "trong", "voi", "duoc", "co",
            "khong", "mot", "nay", "do", "theo", "tu", "den", "hien", "dang", "nhat", "gan", "day", "hay", "tong", "hop",
            "thong", "tin", "so", "sanh", "ra", "sao", "ai", "bao", "nhieu", "ngay", "da", "se", "thi", "ma", "neu", "de",
            "o", "tai", "con", "cung", "rat", "hon", "lai", "khi", "cong", "ty", "chung", "khoan", "phieu", "cao",
            "nhan", "dinh", "doi", "quan", "diem", "nhu", "the", "nhung", "gi", "bang", "hoac", "minh", "toi",
            // English
            "a", "an", "of", "and", "or", "is", "are", "what", "which", "about", "in", "on", "for", "to", "with", "by",
            "from", "does", "do", "how", "why", "who", "report", "reports", "stock", "stocks", "any", "there"));

    public static QueryAnalysis of(String question, List<String> knownBrokerages) {
        String folded = " " + Keywords.normalize(question) + " ";
        List<String> brokerages = new ArrayList<>();
        Set<String> brokerageTokens = new LinkedHashSet<>();
        for (String brokerage : knownBrokerages) {
            String name = Keywords.normalize(brokerage);
            if (!name.isEmpty() && folded.matches("(?s).*[^\\p{L}\\p{N}]" + java.util.regex.Pattern.quote(name) + "[^\\p{L}\\p{N}].*")) {
                brokerages.add(brokerage);
                brokerageTokens.add(brokerage.toUpperCase(java.util.Locale.ROOT));
            }
        }
        List<String> tickers = TickerDetector.detect(question).stream()
                .filter(ticker -> !brokerageTokens.contains(ticker))
                .toList();

        Set<String> terms = new LinkedHashSet<>();
        for (String token : folded.split("[^\\p{L}\\p{N}.,%]+")) {
            String term = token.replaceAll("^[.,%]+|[.,]+$", "");
            if (term.length() >= 2 && !STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return new QueryAnalysis(tickers, List.copyOf(brokerages), List.copyOf(terms));
    }

    /**
     * Share of the question's terms that appear in the (accent-folded) text.
     */
    public double coverage(String foldedText) {
        if (terms.isEmpty()) {
            return 0;
        }
        String padded = " " + foldedText.replaceAll("[^\\p{L}\\p{N}.,%]+", " ") + " ";
        long found = terms.stream().filter(term -> padded.contains(" " + term + " ")).count();
        return (double) found / terms.size();
    }

    /**
     * Query string for PostgreSQL {@code websearch_to_tsquery}: any of the terms.
     */
    public String lexicalQuery() {
        return String.join(" or ", terms.stream().map(term -> term.replace("\"", "").replace("-", " ")).toList());
    }
}

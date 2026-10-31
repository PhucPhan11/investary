package com.investary.knowledge.search;

import java.time.LocalDate;
import java.util.Locale;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * Optional retrieval filters. {@code from}/{@code to} apply to the source date (publication date, or
 * the email sent date when no publication date is known).
 *
 * @param ticker matches chunks that name the ticker, or documents whose metadata ticker it is
 */
public record SearchFilters(String ticker, String brokerage, KnowledgeSourceType sourceType, LocalDate from, LocalDate to) {

    public static final SearchFilters NONE = new SearchFilters(null, null, null, null, null);

    public SearchFilters {
        ticker = blankToNull(ticker) == null ? null : ticker.strip().toUpperCase(Locale.ROOT);
        brokerage = blankToNull(brokerage);
    }

    public SearchFilters withBrokerage(String value) {
        return new SearchFilters(ticker, value, sourceType, from, to);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

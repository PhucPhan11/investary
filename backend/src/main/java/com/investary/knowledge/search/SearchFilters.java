package com.investary.knowledge.search;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * Optional retrieval filters. {@code from}/{@code to} apply to the source date (publication date, or
 * the email sent date when no publication date is known).
 *
 * @param ticker      matches chunks that name the ticker, or documents whose metadata ticker it is
 * @param documentIds with {@code messageIds}, restricts retrieval to these library documents and emails (a notebook's
 *                    sources). Both {@code null} means no restriction; two empty sets mean "no sources", so nothing matches.
 */
public record SearchFilters(String ticker, String brokerage, KnowledgeSourceType sourceType, LocalDate from, LocalDate to,
                            Set<UUID> documentIds, Set<UUID> messageIds) {

    public static final SearchFilters NONE = new SearchFilters(null, null, null, null, null);

    public SearchFilters {
        ticker = blankToNull(ticker) == null ? null : ticker.strip().toUpperCase(Locale.ROOT);
        brokerage = blankToNull(brokerage);
        if (documentIds != null || messageIds != null) {
            documentIds = documentIds == null ? Set.of() : Set.copyOf(documentIds);
            messageIds = messageIds == null ? Set.of() : Set.copyOf(messageIds);
        }
    }

    public SearchFilters(String ticker, String brokerage, KnowledgeSourceType sourceType, LocalDate from, LocalDate to) {
        this(ticker, brokerage, sourceType, from, to, null, null);
    }

    /** Restricts these filters to the given documents and emails. */
    public SearchFilters scopedTo(Set<UUID> documents, Set<UUID> messages) {
        return new SearchFilters(ticker, brokerage, sourceType, from, to,
                documents == null ? Set.of() : documents, messages == null ? Set.of() : messages);
    }

    public boolean scoped() {
        return documentIds != null;
    }

    /** True when the filters are scoped to an empty set of sources, so no chunk can match. */
    public boolean emptyScope() {
        return scoped() && documentIds.isEmpty() && messageIds.isEmpty();
    }

    public SearchFilters withBrokerage(String value) {
        return new SearchFilters(ticker, value, sourceType, from, to, documentIds, messageIds);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

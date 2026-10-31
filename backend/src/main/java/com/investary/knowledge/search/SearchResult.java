package com.investary.knowledge.search;

import java.util.List;

/**
 * @param coverageGaps brokerages named in the question or filter that have no matching indexed evidence
 */
public record SearchResult(List<SearchHit> hits, Mode mode, QueryAnalysis analysis, List<String> coverageGaps) {

    public enum Mode {
        /** Vector similarity and full-text search. */
        HYBRID,
        /** No embedding model configured: full-text and ticker matching only. */
        LEXICAL_ONLY
    }
}

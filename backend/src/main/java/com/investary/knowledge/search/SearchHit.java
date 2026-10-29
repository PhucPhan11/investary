package com.investary.knowledge.search;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * One retrieved chunk with everything needed to cite it. All values come from stored records.
 *
 * @param sourceId    document id or email id
 * @param sourceTicker ticker from document metadata, if the user entered one
 * @param similarity  cosine similarity to the question, {@code null} when the vector leg was not used
 * @param score       fused ranking score (higher is better); only meaningful within one result list
 * @param matchedBy   which retrieval signals found this chunk
 */
public record SearchHit(UUID chunkId, UUID knowledgeSourceId, KnowledgeSourceType sourceType, UUID sourceId,
                        String title, String brokerage, LocalDate publishedOn, String dateBasis,
                        Integer pageStart, Integer pageEnd, String section, int chunkIndex, String content,
                        List<String> tickers, String sourceTicker, String senderName, String senderAddress, Instant sentAt,
                        Double similarity, double score, Set<MatchType> matchedBy) {

    public enum MatchType {
        VECTOR,
        LEXICAL,
        TICKER
    }
}

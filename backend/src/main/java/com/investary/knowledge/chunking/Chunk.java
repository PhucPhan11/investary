package com.investary.knowledge.chunking;

import java.util.Set;

/**
 * A searchable piece of a source with the location needed for a citation.
 *
 * @param pageStart first PDF page the chunk covers, {@code null} for emails
 * @param pageEnd   last PDF page the chunk covers, {@code null} for emails
 * @param section   email section heading path, {@code null} for PDFs or text before the first heading
 * @param tickers   tickers named in this chunk's text
 */
public record Chunk(int index, String content, Integer pageStart, Integer pageEnd, String section, String language,
                    int tokens, Set<String> tickers) {
}

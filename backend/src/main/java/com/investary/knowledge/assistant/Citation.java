package com.investary.knowledge.assistant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * A source reference built only from stored records, never from model output. {@code label} is the
 * marker used in the answer text, e.g. {@code S2} for "[S2]".
 *
 * @param publisher   brokerage that published the source, if known
 * @param publishedAt source date; {@code dateBasis} says whether it is a publication or email sent date
 * @param location    human readable page range or email section
 * @param excerpt     verbatim text from the cited chunk
 */
public record Citation(String label, KnowledgeSourceType sourceType, UUID sourceId, String title, String publisher,
                       LocalDate publishedAt, String dateBasis, Integer pageStart, Integer pageEnd, String section,
                       String location, String senderName, String senderAddress, Instant sentAt, UUID chunkId,
                       String excerpt) {
}

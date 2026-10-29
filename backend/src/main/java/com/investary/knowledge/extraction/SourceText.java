package com.investary.knowledge.extraction;

import java.time.LocalDate;
import java.util.UUID;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * A source ready for chunking: its citation metadata plus extracted text.
 *
 * @param sourceId     document id or email id
 * @param title        document file name (without extension) or email subject
 * @param publishedOn  publication date, or the date the email was sent; see {@code dateBasis}
 * @param dateBasis    where {@code publishedOn} comes from; {@code null} when there is no date
 * @param sourceTicker ticker entered by the user for a document; never guessed from text
 */
public record SourceText(KnowledgeSourceType type, UUID sourceId, String title, String brokerage, LocalDate publishedOn,
                         DateBasis dateBasis, String sourceTicker, ExtractedText text) {

    public enum DateBasis {
        /** Publication date from document metadata. */
        PUBLISHED,
        /** Sent date of the email (or of the earliest email that linked the report). */
        EMAIL_SENT
    }
}

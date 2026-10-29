package com.investary.knowledge.extraction;

import java.util.List;

/**
 * Result of text extraction. Either there are segments to index, or {@code skipReason} says why the
 * source cannot be indexed (it is then recorded as SKIPPED, never as an empty successful index).
 *
 * @param pageCount     number of PDF pages, {@code null} for emails
 * @param skippedBlocks email blocks left out as boilerplate or duplicate language versions
 */
public record ExtractedText(List<TextSegment> segments, Integer pageCount, SkipReason skipReason, int skippedBlocks) {

    public static ExtractedText of(List<TextSegment> segments, Integer pageCount, int skippedBlocks) {
        return new ExtractedText(List.copyOf(segments), pageCount, segments.isEmpty() ? SkipReason.NO_TEXT : null, skippedBlocks);
    }

    public static ExtractedText skipped(SkipReason reason, Integer pageCount) {
        return new ExtractedText(List.of(), pageCount, reason, 0);
    }

    public boolean isSkipped() {
        return skipReason != null;
    }

    public enum SkipReason {
        /** The PDF has (almost) no text layer; it needs OCR, which is not implemented yet. */
        OCR_REQUIRED,
        /** No indexable text after cleaning. */
        NO_TEXT,
        /** The email could not be parsed in Phase 2, so there is no reliable text. */
        PARSING_FAILED
    }
}

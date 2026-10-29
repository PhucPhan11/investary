package com.investary.research.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Parsed content of a source message, handed to the research module for link classification and
 * research-item extraction. Kept independent of the email module's types so research does not
 * depend on email.
 */
public record ResearchSource(UUID sourceMessageId, String brokerage, Instant publishedAt,
                             List<Block> blocks, List<Link> links) {

    /**
     * @param headingLevel 0 for body text, 1 for top-level headings, 2 for sub-headings
     * @param lead         bold label at the start of the block, e.g. {@code "VN-Index:"}
     */
    public record Block(int index, String text, int headingLevel, String section, String lead, String language) {
    }

    public record Link(int position, String originalUrl, String targetUrl, String host,
                       String displayText, String section, String language) {
    }
}

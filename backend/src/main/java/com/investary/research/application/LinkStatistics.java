package com.investary.research.application;

/**
 * Per-message link counters for list views.
 *
 * @param researchLinks links classified as research reports
 * @param documents     distinct library documents linked to the message (downloads and attachments)
 */
public record LinkStatistics(int totalLinks, int researchLinks, int documents, int downloading, int failed) {

    public static final LinkStatistics EMPTY = new LinkStatistics(0, 0, 0, 0, 0);
}

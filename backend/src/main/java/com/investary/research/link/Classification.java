package com.investary.research.link;

/**
 * Outcome of link classification.
 *
 * @param rule       name of the rule that decided the category, stored for provenance
 * @param tickerHint ticker found in the link text or file name, if any
 * @param pdf        whether the target URL path ends with {@code .pdf}
 */
public record Classification(LinkCategory category, String rule, String tickerHint, boolean pdf) {

    public LinkStatus initialStatus() {
        if (category == LinkCategory.NAVIGATION || category == LinkCategory.PROMOTION) {
            return LinkStatus.SKIPPED;
        }
        return category.isResearch() && pdf ? LinkStatus.READY : LinkStatus.DISCOVERED;
    }
}

package com.investary.knowledge.assistant;

import java.util.ArrayList;
import java.util.List;

import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.SearchHit;

/**
 * Labels evidence S1..Sn and builds citations from the stored chunk behind a label, never from model
 * text. Shared by the research assistant and portfolio intelligence.
 */
public final class Citations {

    private static final int EXCERPT_LENGTH = 400;

    private Citations() {
    }

    /** Labels hits S1..Sn in rank order until {@code maxChars} of source text is used (always at least one). */
    public static List<LabeledHit> label(List<SearchHit> hits, int maxChars) {
        List<LabeledHit> labeled = new ArrayList<>();
        int used = 0;
        for (SearchHit hit : hits) {
            int size = hit.content().length();
            if (!labeled.isEmpty() && used + size > maxChars) {
                continue;
            }
            used += size;
            labeled.add(new LabeledHit("S" + (labeled.size() + 1), hit));
        }
        return labeled;
    }

    /**
     * @param terms question terms used to pick the most relevant excerpt
     */
    public static Citation of(LabeledHit labeled, List<String> terms) {
        SearchHit hit = labeled.hit();
        return new Citation(labeled.label(), hit.sourceType(), hit.sourceId(), hit.title(), hit.brokerage(), hit.publishedOn(),
                hit.dateBasis(), hit.pageStart(), hit.pageEnd(), hit.section(), PromptBuilder.location(hit), hit.senderName(),
                hit.senderAddress(), hit.sentAt(), hit.chunkId(), Excerpts.best(hit.content(), terms, EXCERPT_LENGTH));
    }
}

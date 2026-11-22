package com.investary.notebook.domain;

/** What a generated note contains. CONSENSUS and TIMELINE are about one ticker. */
public enum NoteKind {
    SUMMARY,
    CONSENSUS,
    TIMELINE,
    FAQ;

    public boolean perTicker() {
        return this == CONSENSUS || this == TIMELINE;
    }
}

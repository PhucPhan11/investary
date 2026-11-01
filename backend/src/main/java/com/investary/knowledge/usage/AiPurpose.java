package com.investary.knowledge.usage;

/** Why a model was called; automatic purposes count against the daily token budget. */
public enum AiPurpose {
    ASSESSMENT(true),
    BRIEF(true),
    RESEARCH_ASSISTANT(false),
    PORTFOLIO_ASSISTANT(false),
    INDEXING(false),
    SEARCH(false),
    OTHER(false);

    private final boolean automatic;

    AiPurpose(boolean automatic) {
        this.automatic = automatic;
    }

    /** Started by background automation rather than by a question the user asked. */
    public boolean automatic() {
        return automatic;
    }
}

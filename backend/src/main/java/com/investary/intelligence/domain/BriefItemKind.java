package com.investary.intelligence.domain;

/** Keeps verified data, brokerage statements and model interpretation apart. */
public enum BriefItemKind {
    /** Computed by Investary from data the user entered (holdings, prices, counts, dates). */
    PORTFOLIO_FACT,
    /** What a brokerage source says; always cited and quoted or paraphrased from stored text. */
    SOURCE_CLAIM,
    /** Model text, validated against the cited sources; an interpretation, not a fact. */
    AI_INTERPRETATION,
    /** Investary's own note: a limitation or a suggested follow-up (never a trading instruction). */
    SYSTEM_NOTE
}

package com.investary.journal.domain;

public enum EvidenceKind {
    DOCUMENT,
    EMAIL,
    RESEARCH_ITEM,
    /** A stored knowledge chunk, as cited by the research assistant. */
    CITATION,
    IMPACT_ASSESSMENT
}

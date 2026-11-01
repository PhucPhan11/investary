package com.investary.intelligence.domain;

/** What the reporting period contained. */
public enum ResearchState {
    /** At least one source became searchable in the period. */
    NEW_RESEARCH,
    /** Nothing became searchable and nothing is waiting to be processed. */
    NO_MATERIAL_RESEARCH,
    /** Nothing became searchable, but sources imported in the period are not processed yet (or failed). */
    PENDING_ONLY
}

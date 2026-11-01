package com.investary.intelligence.domain;

/** Why a source is listed on a brief. */
public enum BriefSourceRole {
    /** Sent to the model and cited by at least one item. */
    CITED,
    /** Part of the evidence package but not cited. */
    INCLUDED,
    /** Imported in the period but not searchable yet (or processing failed). */
    PENDING,
    /** Eligible, but left out by the source or context limits. */
    OMITTED
}

package com.investary.journal.domain;

/**
 * {@code DRAFT -> DECIDED -> EXECUTED}, or {@code DRAFT | DECIDED -> CANCELLED}. EXECUTED and CANCELLED are final.
 */
public enum DecisionStatus {
    DRAFT,
    DECIDED,
    EXECUTED,
    CANCELLED
}

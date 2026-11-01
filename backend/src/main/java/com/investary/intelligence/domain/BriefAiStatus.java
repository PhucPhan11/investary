package com.investary.intelligence.domain;

/** What happened with the optional model part of a brief. */
public enum BriefAiStatus {
    COMPLETED,
    NOT_CONFIGURED,
    FAILED,
    /** No new evidence, so the model was not called. */
    SKIPPED_NO_EVIDENCE
}

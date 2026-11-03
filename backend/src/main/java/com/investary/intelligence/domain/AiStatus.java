package com.investary.intelligence.domain;

/**
 * Whether an AI interpretation was added to a deterministic impact assessment.
 */
public enum AiStatus {
    /** No chat model is configured; the assessment is deterministic only. */
    NOT_CONFIGURED,
    /** The model was called and its output validated. */
    COMPLETED,
    /** No holding had evidence, so the model was not called. */
    SKIPPED_NO_EVIDENCE
}

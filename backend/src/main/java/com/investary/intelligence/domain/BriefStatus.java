package com.investary.intelligence.domain;

/**
 * Result of a brief generation: COMPLETED (model part validated, or not needed), PARTIAL (deterministic
 * sections only: no model, model failure or unusable model output), FAILED (no content), SKIPPED (nothing
 * new and the no-research mode is SKIP).
 */
public enum BriefStatus {
    COMPLETED,
    PARTIAL,
    FAILED,
    SKIPPED
}

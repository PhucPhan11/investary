package com.investary.automation.domain;

/**
 * Status of a workflow stage and of the workflow itself. SKIPPED means there was nothing to do (no eligible
 * input or a disabled option), which is not a failure.
 */
public enum StageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    SKIPPED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == SKIPPED;
    }
}

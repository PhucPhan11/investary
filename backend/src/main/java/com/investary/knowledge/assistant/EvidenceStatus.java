package com.investary.knowledge.assistant;

/**
 * How well the retrieved sources support the answer. The backend can only lower the model's own
 * assessment, never raise it.
 */
public enum EvidenceStatus {
    /** Every material claim is cited and the citations passed validation. */
    SUPPORTED,
    /** Some of the answer is supported; unsupported or unverified parts are listed in the warnings. */
    PARTIAL,
    /** The knowledge base does not contain enough evidence to answer. */
    INSUFFICIENT;

    public EvidenceStatus atMost(EvidenceStatus cap) {
        return ordinal() >= cap.ordinal() ? this : cap;
    }
}

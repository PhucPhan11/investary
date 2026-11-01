package com.investary.intelligence.domain;

/** How a successful brief job ended. */
public enum BriefJobOutcome {
    /** A new brief (or revision) was written. */
    CREATED,
    /** The inputs equal those of the latest revision; nothing was written and the model was not called. */
    UNCHANGED,
    /** Nothing new and the no-research mode is SKIP; a SKIPPED row keeps the period. */
    SKIPPED
}

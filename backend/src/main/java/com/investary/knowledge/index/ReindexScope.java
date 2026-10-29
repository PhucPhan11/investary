package com.investary.knowledge.index;

public enum ReindexScope {
    /** Every tracked source. Unchanged sources are confirmed without new embedding calls. */
    ALL,
    /** Sources whose last indexing failed. */
    FAILED,
    /** Sources indexed with a different chunking or embedding configuration than the active one. */
    STALE,
    /** Documents and emails that are not tracked yet (backfill). */
    MISSING
}

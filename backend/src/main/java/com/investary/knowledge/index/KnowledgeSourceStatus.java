package com.investary.knowledge.index;

public enum KnowledgeSourceStatus {
    /** Waiting to be indexed (new, requested again, or retrying after a failure). */
    PENDING,
    /** Claimed by a worker. */
    PROCESSING,
    /** Chunks and vectors are stored and searchable (if the index version is current). */
    INDEXED,
    /** Indexing failed after all attempts, or with an error retrying cannot fix. */
    FAILED,
    /** Nothing to index, e.g. a scanned PDF that needs OCR; see {@code status_reason}. */
    SKIPPED
}

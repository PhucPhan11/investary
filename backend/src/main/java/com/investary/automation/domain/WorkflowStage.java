package com.investary.automation.domain;

/** Stages of a research workflow, in order. EMAIL_* stages are SKIPPED for a library document. */
public enum WorkflowStage {
    EMAIL_IMPORTED,
    EMAIL_PARSED,
    LINKS_CLASSIFIED,
    DOCUMENTS_PROCESSED,
    KNOWLEDGE_INDEXED,
    PORTFOLIO_MATCHED,
    IMPACT_ASSESSED,
    COMPLETED
}

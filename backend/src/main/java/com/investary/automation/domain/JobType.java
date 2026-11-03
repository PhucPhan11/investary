package com.investary.automation.domain;

/** Automation job types; each drives one workflow stage (PREPARE covers the import stages). */
public enum JobType {
    PREPARE,
    DOWNLOADS,
    INDEX,
    MATCH,
    ASSESS;

    public WorkflowStage stage() {
        return switch (this) {
            case PREPARE -> WorkflowStage.LINKS_CLASSIFIED;
            case DOWNLOADS -> WorkflowStage.DOCUMENTS_PROCESSED;
            case INDEX -> WorkflowStage.KNOWLEDGE_INDEXED;
            case MATCH -> WorkflowStage.PORTFOLIO_MATCHED;
            case ASSESS -> WorkflowStage.IMPACT_ASSESSED;
        };
    }
}

package com.investary.automation.domain;

/** What started (or restarted) a workflow. */
public enum WorkflowTrigger {
    EMAIL_IMPORT,
    GMAIL,
    DOCUMENT_UPLOAD,
    REPORT_DOWNLOADED,
    MANUAL,
    RECONCILE
}

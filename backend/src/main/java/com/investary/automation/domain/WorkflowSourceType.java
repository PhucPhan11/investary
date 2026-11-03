package com.investary.automation.domain;

/** A workflow processes either an imported email (with its reports) or a document uploaded to the library. */
public enum WorkflowSourceType {
    EMAIL,
    DOCUMENT
}

package com.investary.document.application;

/** Where an uploaded document came from; only library uploads start their own research workflow. */
public enum UploadOrigin {
    LIBRARY,
    EMAIL_ATTACHMENT,
    LINKED_REPORT
}

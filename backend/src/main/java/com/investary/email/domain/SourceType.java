package com.investary.email.domain;

public enum SourceType {
    EML_UPLOAD,
    /** Imported from a connected Gmail account (original RFC 822 bytes from the Gmail API). */
    GMAIL
}

package com.investary.email.domain;

/**
 * {@code PARTIAL}: some headers or parts could not be decoded; {@code FAILED}: the content could not
 * be analysed at all. In both cases the original EML is kept for reprocessing.
 */
public enum ParsingStatus {
    PARSED,
    PARTIAL,
    FAILED
}

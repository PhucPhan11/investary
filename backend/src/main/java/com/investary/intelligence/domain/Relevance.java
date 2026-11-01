package com.investary.intelligence.domain;

/** Relevance of a new source to the brief's portfolio, decided deterministically from tickers. */
public enum Relevance {
    /** A report about a held instrument (its own ticker, title, or the only ticker named). */
    DIRECT,
    /** A held instrument is one of several tickers named. */
    MENTION,
    /** Market-wide commentary without tickers. */
    MARKET,
    /** About instruments that are not held. */
    OTHER
}

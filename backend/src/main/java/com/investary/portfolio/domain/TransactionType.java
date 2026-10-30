package com.investary.portfolio.domain;

/**
 * OPENING_POSITION records a holding bought before tracking started; it is costed like a BUY.
 */
public enum TransactionType {
    BUY, SELL, OPENING_POSITION
}

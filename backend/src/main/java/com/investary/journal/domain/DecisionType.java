package com.investary.journal.domain;

public enum DecisionType {
    BUY,
    SELL,
    HOLD,
    REBALANCE,
    OBSERVE;

    /** BUY, SELL and REBALANCE are executed only through real portfolio transactions that the user links. */
    public boolean requiresTransaction() {
        return this == BUY || this == SELL || this == REBALANCE;
    }
}

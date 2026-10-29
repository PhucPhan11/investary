package com.investary.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects that must not happen if the surrounding transaction rolls back,
 * such as deleting files whose metadata row is being removed.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Runs the action after the current transaction commits, or immediately if no transaction is active.
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

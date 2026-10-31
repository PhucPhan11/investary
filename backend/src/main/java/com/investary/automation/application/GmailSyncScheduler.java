package com.investary.automation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic Gmail sync. Uses the same account lock as Sync Now, so the two never overlap; a tick while a
 * sync runs is simply skipped.
 */
@Component
@ConditionalOnProperty(prefix = "investary.gmail.sync", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class GmailSyncScheduler {

    private final GmailSyncService syncs;

    public GmailSyncScheduler(GmailSyncService syncs) {
        this.syncs = syncs;
    }

    @Scheduled(fixedDelayString = "${investary.gmail.sync.interval:30m}", initialDelayString = "${investary.gmail.sync.initial-delay:2m}")
    public void tick() {
        syncs.runScheduled();
    }
}

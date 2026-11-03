package com.investary.automation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic Gmail sync. Ticks every {@code investary.gmail.sync.tick} (1 minute) and starts a sync once the
 * polling interval (a runtime setting) has passed since the last one started, so a changed interval or the
 * polling switch applies without a restart. Uses the same account lock as Sync Now, so the two never overlap.
 */
@Component
@ConditionalOnProperty(prefix = "investary.gmail.sync", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class GmailSyncScheduler {

    private final GmailSyncService syncs;

    public GmailSyncScheduler(GmailSyncService syncs) {
        this.syncs = syncs;
    }

    @Scheduled(fixedDelayString = "${investary.gmail.sync.tick:1m}", initialDelayString = "${investary.gmail.sync.initial-delay:2m}")
    public void tick() {
        syncs.runScheduled();
    }
}

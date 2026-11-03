package com.investary.automation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class GmailScheduleTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    @Test
    void theNextSyncIsOneIntervalAfterTheLastStartOrNowWhenOverdue() {
        Duration interval = Duration.ofMinutes(30);
        assertThat(GmailSyncService.nextSync(null, interval, NOW)).isEqualTo(NOW);
        assertThat(GmailSyncService.nextSync(NOW.minus(Duration.ofMinutes(10)), interval, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
        assertThat(GmailSyncService.nextSync(NOW.minus(Duration.ofHours(2)), interval, NOW)).isEqualTo(NOW);
    }
}

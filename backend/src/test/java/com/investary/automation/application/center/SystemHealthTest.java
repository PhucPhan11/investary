package com.investary.automation.application.center;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.investary.automation.application.center.SystemHealth.GmailFacts;
import com.investary.automation.application.center.SystemHealth.State;
import com.investary.automation.application.center.SystemHealth.WorkerFacts;
import com.investary.automation.domain.GmailAccountStatus;

class SystemHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final Duration INTERVAL = Duration.ofMinutes(30);

    @Test
    void gmailIsOnlyOperationalAfterARecentSuccessfulSync() {
        assertThat(gmail(false, null, true, false, null).state()).isEqualTo(State.UNAVAILABLE);
        assertThat(gmail(true, null, true, false, null).state()).isEqualTo(State.DISABLED);
        assertThat(gmail(true, GmailAccountStatus.RECONNECT_REQUIRED, true, false, NOW).state()).isEqualTo(State.DEGRADED);
        assertThat(gmail(true, GmailAccountStatus.CONNECTED, true, true, NOW).reason()).isEqualTo("The last sync failed");
        assertThat(gmail(true, GmailAccountStatus.CONNECTED, false, false, NOW).state()).isEqualTo(State.DISABLED);
        assertThat(gmail(true, GmailAccountStatus.CONNECTED, true, false, null).reason()).isEqualTo("No successful sync yet");
        assertThat(gmail(true, GmailAccountStatus.CONNECTED, true, false, NOW.minus(Duration.ofHours(2))).state()).isEqualTo(State.DEGRADED);
        assertThat(gmail(true, GmailAccountStatus.CONNECTED, true, false, NOW.minus(Duration.ofMinutes(20))).state())
                .isEqualTo(State.OPERATIONAL);
    }

    @Test
    void workersNeedARecentHeartbeatAndNoRecentFailures() {
        assertThat(worker(false, true, NOW, 0, 0).state()).isEqualTo(State.DISABLED);
        assertThat(worker(true, false, NOW, 0, 0).state()).isEqualTo(State.DISABLED);
        assertThat(worker(true, true, null, 0, 0).state()).isEqualTo(State.UNAVAILABLE);
        assertThat(worker(true, true, NOW.minus(Duration.ofMinutes(5)), 0, 0).reason()).isEqualTo("The background worker stopped polling");
        assertThat(worker(true, true, NOW.minusSeconds(5), 2, 0).state()).isEqualTo(State.DEGRADED);
        assertThat(worker(true, true, NOW.minusSeconds(5), 0, 1).state()).isEqualTo(State.DEGRADED);
        assertThat(worker(true, true, NOW.minusSeconds(5), 0, 0).state()).isEqualTo(State.OPERATIONAL);
    }

    @Test
    void degradeOnlyLowersAnOperationalComponent() {
        assertThat(SystemHealth.degrade(worker(true, true, NOW, 0, 0), "No chat model").state()).isEqualTo(State.DEGRADED);
        assertThat(SystemHealth.degrade(worker(false, true, NOW, 0, 0), "No chat model").state()).isEqualTo(State.DISABLED);
    }

    private static SystemHealth.ComponentHealth gmail(boolean configured, GmailAccountStatus status, boolean polling, boolean lastFailed,
                                                      Instant lastSuccess) {
        return SystemHealth.gmail(new GmailFacts(configured, status, polling, lastFailed, lastSuccess, INTERVAL), NOW);
    }

    private static SystemHealth.ComponentHealth worker(boolean enabled, boolean present, Instant lastPoll, long failures, long stuck) {
        return SystemHealth.worker("X", new WorkerFacts(enabled, "off", present, lastPoll, Duration.ofSeconds(2), failures, stuck, null), NOW);
    }
}

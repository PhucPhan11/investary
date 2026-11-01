package com.investary.automation.application.center;

import java.time.Duration;
import java.time.Instant;

import com.investary.automation.domain.GmailAccountStatus;

/**
 * Deterministic health of the automation components, computed from stored records and configuration only.
 * A component is never reported OPERATIONAL without evidence: a worker must have polled recently, and Gmail
 * must have synced successfully within a few polling intervals.
 */
public final class SystemHealth {

    /** A worker that has not polled for this many poll intervals (at least a minute) is considered stopped. */
    static final int STALE_POLLS = 5;
    /** Gmail is stale when its last success is older than this many polling intervals. */
    static final int STALE_SYNC_INTERVALS = 3;

    private SystemHealth() {
    }

    public enum State {
        OPERATIONAL,
        DEGRADED,
        DISABLED,
        UNAVAILABLE
    }

    /**
     * @param lastActivityAt latest record of work by the component, or null
     * @param checkedAt      when this was computed
     */
    public record ComponentHealth(String component, State state, String reason, Instant lastActivityAt, Instant checkedAt) {
    }

    /**
     * @param accountStatus null when no account was ever connected
     */
    public record GmailFacts(boolean configured, GmailAccountStatus accountStatus, boolean pollingEnabled, boolean lastRunFailed,
                             Instant lastSuccessAt, Duration interval) {
    }

    /**
     * @param enabled          the feature is switched on
     * @param disabledReason   shown when it is off
     * @param workerPresent    the background worker runs in this process
     * @param lastPollAt       worker heartbeat, or null before its first poll
     * @param failures         failed jobs in the last 24 hours
     * @param stuck            jobs waiting for longer than expected
     */
    public record WorkerFacts(boolean enabled, String disabledReason, boolean workerPresent, Instant lastPollAt, Duration pollInterval,
                              long failures, long stuck, Instant lastActivityAt) {
    }

    public static ComponentHealth gmail(GmailFacts facts, Instant now) {
        String name = "GMAIL";
        if (!facts.configured()) {
            return new ComponentHealth(name, State.UNAVAILABLE, "The Gmail OAuth client is not configured", facts.lastSuccessAt(), now);
        }
        if (facts.accountStatus() == null || facts.accountStatus() == GmailAccountStatus.DISCONNECTED) {
            return new ComponentHealth(name, State.DISABLED, "No Gmail account is connected", facts.lastSuccessAt(), now);
        }
        if (facts.accountStatus() == GmailAccountStatus.RECONNECT_REQUIRED) {
            return new ComponentHealth(name, State.DEGRADED, "The Gmail authorization expired; reconnect Gmail", facts.lastSuccessAt(), now);
        }
        if (facts.lastRunFailed()) {
            return new ComponentHealth(name, State.DEGRADED, "The last sync failed", facts.lastSuccessAt(), now);
        }
        if (!facts.pollingEnabled()) {
            return new ComponentHealth(name, State.DISABLED, "Automatic polling is off; Sync now still works", facts.lastSuccessAt(), now);
        }
        if (facts.lastSuccessAt() == null) {
            return new ComponentHealth(name, State.DEGRADED, "No successful sync yet", null, now);
        }
        if (Duration.between(facts.lastSuccessAt(), now).compareTo(facts.interval().multipliedBy(STALE_SYNC_INTERVALS)) > 0) {
            return new ComponentHealth(name, State.DEGRADED, "No successful sync for more than " + STALE_SYNC_INTERVALS + " polling intervals",
                    facts.lastSuccessAt(), now);
        }
        return new ComponentHealth(name, State.OPERATIONAL, "Synced recently", facts.lastSuccessAt(), now);
    }

    public static ComponentHealth worker(String name, WorkerFacts facts, Instant now) {
        if (!facts.enabled()) {
            return new ComponentHealth(name, State.DISABLED, facts.disabledReason(), facts.lastActivityAt(), now);
        }
        if (!facts.workerPresent()) {
            return new ComponentHealth(name, State.DISABLED, "The background worker is disabled in the configuration", facts.lastActivityAt(), now);
        }
        Duration staleAfter = facts.pollInterval().multipliedBy(STALE_POLLS);
        if (staleAfter.compareTo(Duration.ofMinutes(1)) < 0) {
            staleAfter = Duration.ofMinutes(1);
        }
        if (facts.lastPollAt() == null) {
            return new ComponentHealth(name, State.UNAVAILABLE, "The background worker has not run yet", facts.lastActivityAt(), now);
        }
        if (Duration.between(facts.lastPollAt(), now).compareTo(staleAfter) > 0) {
            return new ComponentHealth(name, State.UNAVAILABLE, "The background worker stopped polling", facts.lastActivityAt(), now);
        }
        if (facts.failures() > 0) {
            return new ComponentHealth(name, State.DEGRADED, facts.failures() + " failure(s) in the last 24 hours", facts.lastActivityAt(), now);
        }
        if (facts.stuck() > 0) {
            return new ComponentHealth(name, State.DEGRADED, facts.stuck() + " job(s) waiting longer than expected", facts.lastActivityAt(), now);
        }
        return new ComponentHealth(name, State.OPERATIONAL, "Running", facts.lastActivityAt(), now);
    }

    /** Degrades an operational component with a reason (e.g. no AI model for briefs). */
    public static ComponentHealth degrade(ComponentHealth health, String reason) {
        return health.state() == State.OPERATIONAL ? new ComponentHealth(health.component(), State.DEGRADED, reason, health.lastActivityAt(),
                health.checkedAt()) : health;
    }
}

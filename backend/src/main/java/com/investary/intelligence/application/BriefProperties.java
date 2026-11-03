package com.investary.intelligence.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import com.investary.common.util.Hashing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Daily investment brief (Phase 5 / Milestone 3).
 *
 * @param enabled             generate scheduled briefs and accept manual requests
 * @param schedule            when briefs are generated and which zone defines the reporting periods
 * @param catchUpWindow       after downtime, a missed run is still generated when it is at most this old
 * @param noResearchMode      MINIMAL: a short "no material new research" brief; SKIP: only a SKIPPED record
 * @param maxSources          sources in one brief's evidence package
 * @param chunksPerSource     chunks per source in the evidence package
 * @param maxContextChars     source text sent to the model
 * @param oldPublicationDays  a source published more than this many days before the period is "old research"
 * @param maxBriefsPerRun     scheduled briefs (one per portfolio) per run
 * @param maxAttempts         attempts per generation job
 * @param retryBackoff        first retry delay; doubles per attempt, with jitter
 * @param aiTimeout           longest wait for the model (including the wait for a shared chat permit)
 */
@Validated
@ConfigurationProperties("investary.brief")
public record BriefProperties(
        boolean enabled,
        @Valid @NotNull Schedule schedule,
        @NotNull Duration catchUpWindow,
        @NotNull NoResearchMode noResearchMode,
        @Min(1) int maxSources,
        @Min(1) int chunksPerSource,
        @Min(1000) int maxContextChars,
        @Min(0) int oldPublicationDays,
        @Min(1) int maxBriefsPerRun,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff,
        @NotNull Duration aiTimeout,
        @Valid @NotNull Worker worker) {

    /**
     * @param cron Spring cron expression (seconds first), evaluated in {@code zone}
     * @param zone IANA zone id; reporting periods never use the JVM default zone
     */
    public record Schedule(@NotBlank String cron, @NotBlank String zone) {

        public Schedule {
            if (cron != null && !cron.isBlank()) {
                CronExpression.parse(cron);
            }
            if (zone != null && !zone.isBlank()) {
                ZoneId.of(zone);
            }
        }

        public ZoneId zoneId() {
            return ZoneId.of(zone);
        }
    }

    /**
     * @param enabled     run the scheduler and the generation worker in this process
     * @param concurrency briefs generated at the same time
     * @param lease       a RUNNING job older than this is considered abandoned and re-queued
     */
    public record Worker(boolean enabled, @Min(1) int concurrency, @NotNull Duration pollInterval, @NotNull Duration lease) {
    }

    public enum NoResearchMode {
        MINIMAL,
        SKIP
    }

    /**
     * Short fingerprint of the settings that change what a brief contains. Scheduled briefs are unique per
     * scope, period and this version.
     */
    public String configVersion() {
        String canonical = String.join("|", schedule.cron(), schedule.zone(), noResearchMode.name(), Integer.toString(maxSources),
                Integer.toString(chunksPerSource), Integer.toString(maxContextChars), Integer.toString(oldPublicationDays),
                BriefPrompts.VERSION);
        return "b-" + Hashing.sha256(canonical.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }
}

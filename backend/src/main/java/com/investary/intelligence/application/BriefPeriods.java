package com.investary.intelligence.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/**
 * Reporting periods of the daily brief: the half-open interval [previous fire time, fire time) of the
 * configured cron, evaluated in the configured zone (never the JVM default zone). Daylight-saving zones
 * work because the cron is evaluated on {@link ZonedDateTime}; such a period can be 23 or 25 hours long.
 */
final class BriefPeriods {

    /** How far back to look for fire times; covers monthly schedules. */
    private static final Duration LOOKBACK = Duration.ofDays(32);

    private final CronExpression cron;
    private final ZoneId zone;

    BriefPeriods(String cron, ZoneId zone) {
        this.cron = CronExpression.parse(cron);
        this.zone = zone;
    }

    ZoneId zone() {
        return zone;
    }

    /** The latest fire time at or before {@code now}, or null when there is none in the look-back window. */
    ZonedDateTime latestFireAtOrBefore(Instant now) {
        ZonedDateTime limit = now.atZone(zone);
        ZonedDateTime cursor = limit.minus(LOOKBACK);
        ZonedDateTime last = null;
        ZonedDateTime next;
        while ((next = cron.next(cursor)) != null && !next.isAfter(limit)) {
            last = next;
            cursor = next;
        }
        return last;
    }

    /** The scheduled period that ends at {@code fire}. */
    Period scheduled(ZonedDateTime fire) {
        ZonedDateTime previous = previousFire(fire);
        if (previous == null) {
            throw new InvalidPeriodException("The schedule has no earlier run within " + LOOKBACK.toDays() + " days");
        }
        return new Period(previous.toInstant(), fire.toInstant(), fire.toLocalDate(), zone.getId(), false);
    }

    /**
     * The period ending at the first fire time on {@code date}. When that time is still ahead, the period
     * ends at {@code now} (truncated to the second) and is marked partial.
     */
    Period forDate(LocalDate date, Instant now) {
        ZonedDateTime fire = cron.next(date.atStartOfDay(zone).minusNanos(1));
        if (fire == null || !fire.toLocalDate().equals(date)) {
            throw new InvalidPeriodException("The brief schedule has no run on " + date);
        }
        if (!fire.toInstant().isAfter(now)) {
            return scheduled(fire);
        }
        ZonedDateTime previous = previousFire(fire);
        Instant end = now.truncatedTo(ChronoUnit.SECONDS);
        if (previous == null || !end.isAfter(previous.toInstant())) {
            throw new InvalidPeriodException("The reporting period for " + date + " has not started yet");
        }
        return new Period(previous.toInstant(), end, date, zone.getId(), true);
    }

    private ZonedDateTime previousFire(ZonedDateTime fire) {
        ZonedDateTime cursor = fire.minus(LOOKBACK);
        ZonedDateTime last = null;
        ZonedDateTime next;
        while ((next = cron.next(cursor)) != null && next.isBefore(fire)) {
            last = next;
            cursor = next;
        }
        return last;
    }

    /**
     * @param businessDate local date of the period end (the brief's date)
     * @param partial      the period was cut at the request time because it had not ended yet
     */
    record Period(Instant start, Instant end, LocalDate businessDate, String zone, boolean partial) {
    }

    static class InvalidPeriodException extends ApiException {
        InvalidPeriodException(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.INVALID_BRIEF_PERIOD, "Invalid brief period", detail);
        }
    }
}

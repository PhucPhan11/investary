package com.investary.intelligence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.investary.intelligence.application.BriefPeriods.Period;

class BriefPeriodsTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final BriefPeriods periods = new BriefPeriods("0 0 8 * * *", VIETNAM);

    @Test
    void scheduledPeriodIsTheDayBeforeEightOClockInTheConfiguredZone() {
        // 09:30 in Ho Chi Minh City = 02:30 UTC; the JVM zone plays no part.
        var fire = periods.latestFireAtOrBefore(Instant.parse("2026-11-01T02:30:00Z"));
        Period period = periods.scheduled(fire);

        assertThat(period.start()).isEqualTo(Instant.parse("2026-10-31T01:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
        assertThat(period.businessDate()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(period.zone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(period.partial()).isFalse();
    }

    @Test
    void beforeTheRunTheLatestFireIsYesterdays() {
        assertThat(periods.latestFireAtOrBefore(Instant.parse("2026-11-01T00:59:59Z")).toInstant())
                .isEqualTo(Instant.parse("2026-10-31T01:00:00Z"));
        // The fire time itself is due (periods are [start, end)).
        assertThat(periods.latestFireAtOrBefore(Instant.parse("2026-11-01T01:00:00Z")).toInstant())
                .isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
    }

    @Test
    void sameInstantGivesDifferentBusinessDatesInDifferentZones() {
        Instant now = Instant.parse("2026-11-02T01:30:00Z"); // 08:30 in Vietnam, 01:30 in London
        Period vietnam = periods.scheduled(periods.latestFireAtOrBefore(now));
        BriefPeriods utc = new BriefPeriods("0 0 8 * * *", ZoneId.of("UTC"));
        Period london = utc.scheduled(utc.latestFireAtOrBefore(now));

        assertThat(vietnam.businessDate()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(london.businessDate()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(london.end()).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
    }

    @Test
    void manualRequestForTodayBeforeTheRunIsAPartialPeriodEndingNow() {
        Instant now = Instant.parse("2026-11-01T00:30:15.500Z");
        Period period = periods.forDate(LocalDate.parse("2026-11-01"), now);

        assertThat(period.partial()).isTrue();
        assertThat(period.start()).isEqualTo(Instant.parse("2026-10-31T01:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-11-01T00:30:15Z"));
    }

    @Test
    void manualRequestForAPastDateIsTheFullScheduledPeriod() {
        Period period = periods.forDate(LocalDate.parse("2026-10-20"), Instant.parse("2026-11-01T02:00:00Z"));

        assertThat(period.partial()).isFalse();
        assertThat(period.start()).isEqualTo(Instant.parse("2026-10-19T01:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"));
    }

    @Test
    void futurePeriodsAreRejected() {
        assertThatThrownBy(() -> periods.forDate(LocalDate.parse("2026-11-05"), Instant.parse("2026-11-01T02:00:00Z")))
                .isInstanceOf(BriefPeriods.InvalidPeriodException.class);
    }

    @Test
    void daylightSavingChangeGivesA25HourPeriod() {
        // Europe/Berlin leaves summer time on 25 October 2026.
        BriefPeriods berlin = new BriefPeriods("0 0 8 * * *", ZoneId.of("Europe/Berlin"));
        Period period = berlin.forDate(LocalDate.parse("2026-10-25"), Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(period.start()).isEqualTo(Instant.parse("2026-10-24T06:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
        assertThat(Duration.between(period.start(), period.end())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void weekdayScheduleSkipsTheWeekend() {
        BriefPeriods weekdays = new BriefPeriods("0 0 8 * * MON-FRI", VIETNAM);
        // Monday 2 November 2026: the period starts at Friday's run.
        Period monday = weekdays.forDate(LocalDate.parse("2026-11-02"), Instant.parse("2026-11-03T00:00:00Z"));

        assertThat(monday.start()).isEqualTo(Instant.parse("2026-10-30T01:00:00Z"));
        assertThatThrownBy(() -> weekdays.forDate(LocalDate.parse("2026-11-01"), Instant.parse("2026-11-03T00:00:00Z")))
                .isInstanceOf(BriefPeriods.InvalidPeriodException.class);
    }
}

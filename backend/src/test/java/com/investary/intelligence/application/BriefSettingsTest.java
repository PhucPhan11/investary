package com.investary.intelligence.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BriefSettingsTest {

    @Test
    void aDailyTimeAndItsCronConvertBothWays() {
        assertThat(BriefSettings.cron("06:30")).isEqualTo("0 30 6 * * *");
        assertThat(BriefSettings.time("0 0 8 * * *")).isEqualTo("08:00");
        assertThat(BriefSettings.time(BriefSettings.cron("23:05"))).isEqualTo("23:05");
        assertThat(BriefSettings.time("0 0 8 * * MON-FRI")).isNull();
        assertThat(BriefSettings.time("0 */15 * * * *")).isNull();
    }
}

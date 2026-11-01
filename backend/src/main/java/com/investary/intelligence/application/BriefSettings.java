package com.investary.intelligence.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.investary.common.error.ApiException;
import com.investary.common.settings.RuntimeSettings;
import com.investary.common.settings.SettingField;
import com.investary.common.settings.SettingType;
import com.investary.common.settings.SettingsContributor;
import com.investary.portfolio.application.PortfolioQueryService;

/**
 * Effective daily-brief settings: {@link BriefProperties} with the user's overrides (ADR 0012). The scheduled
 * time is stored as {@code HH:mm} and becomes the cron {@code 0 m H * * *}; a configured cron that is not a
 * daily time stays in force until a time is saved. Changing the time or zone changes
 * {@link BriefProperties#configVersion()}, so scheduled briefs start a new series.
 */
@Component
public class BriefSettings implements SettingsContributor {

    public static final String SECTION = "brief";
    private static final Pattern DAILY_CRON = Pattern.compile("0 (\\d{1,2}) (\\d{1,2}) \\* \\* \\*");

    private final BriefProperties properties;
    private final RuntimeSettings settings;
    private final PortfolioQueryService portfolios;
    private final int maxAiBriefsPerDay;

    @Autowired
    public BriefSettings(BriefProperties properties, RuntimeSettings settings, PortfolioQueryService portfolios,
                         @Value("${investary.brief.max-ai-briefs-per-day:" + DEFAULT_MAX_AI_BRIEFS + "}") int maxAiBriefsPerDay) {
        this.properties = properties;
        this.settings = settings;
        this.portfolios = portfolios;
        this.maxAiBriefsPerDay = maxAiBriefsPerDay;
    }

    /** The configured values only, for unit tests. */
    static BriefSettings fixed(BriefProperties properties) {
        return new BriefSettings(properties, null, null, DEFAULT_MAX_AI_BRIEFS);
    }

    /** {@link BriefProperties} with the enabled flag, schedule and no-research mode overrides applied. */
    public BriefProperties current() {
        if (settings == null) {
            return properties;
        }
        String cron = settings.text(SECTION, "scheduleTime").map(BriefSettings::cron).orElse(properties.schedule().cron());
        String zone = settings.text(SECTION, "zone").orElse(properties.schedule().zone());
        BriefProperties.NoResearchMode mode = settings.text(SECTION, "noResearchMode").map(BriefProperties.NoResearchMode::valueOf)
                .orElse(properties.noResearchMode());
        return new BriefProperties(settings.bool(SECTION, "enabled", properties.enabled()), new BriefProperties.Schedule(cron, zone),
                properties.catchUpWindow(), mode, properties.maxSources(), properties.chunksPerSource(), properties.maxContextChars(),
                properties.oldPublicationDays(), properties.maxBriefsPerRun(), properties.maxAttempts(), properties.retryBackoff(),
                properties.aiTimeout(), properties.worker());
    }

    public BriefPeriods periods() {
        BriefProperties current = current();
        return new BriefPeriods(current.schedule().cron(), current.schedule().zoneId());
    }

    /** Portfolio that scheduled briefs are limited to; empty means every portfolio. */
    public Optional<UUID> defaultPortfolioId() {
        return settings == null ? Optional.empty() : settings.uuid(SECTION, "defaultPortfolioId");
    }

    /** Briefs per day (in the brief zone) whose model part may be generated. */
    public int maxAiBriefsPerDay() {
        return settings == null ? maxAiBriefsPerDay : settings.integer("ai", "maxAiBriefsPerDay", maxAiBriefsPerDay);
    }

    static final int DEFAULT_MAX_AI_BRIEFS = 10;

    static String cron(String time) {
        String[] parts = time.split(":");
        return "0 " + Integer.parseInt(parts[1]) + " " + Integer.parseInt(parts[0]) + " * * *";
    }

    /** {@code HH:mm} of a daily cron, or null for any other cron. */
    static String time(String cron) {
        Matcher m = DAILY_CRON.matcher(cron.strip());
        if (!m.matches()) {
            return null;
        }
        int minute = Integer.parseInt(m.group(1));
        int hour = Integer.parseInt(m.group(2));
        return minute < 60 && hour < 24 ? "%02d:%02d".formatted(hour, minute) : null;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.bool(SECTION, "enabled", 0, "Daily brief", "Generate scheduled briefs and accept manual requests",
                        properties::enabled),
                SettingField.of(SECTION, "scheduleTime", SettingType.TIME, 1, "Scheduled time",
                        time(properties.schedule().cron()) == null ? "The configured schedule is a custom cron (" + properties.schedule().cron()
                                + "); saving a time replaces it" : "Local time in the brief time zone",
                        () -> time(properties.schedule().cron())).withNullable(),
                SettingField.of(SECTION, "zone", SettingType.ZONE, 2, "Time zone", "IANA time zone of the schedule and reporting periods",
                        () -> properties.schedule().zone()),
                SettingField.of(SECTION, "defaultPortfolioId", SettingType.UUID, 3, "Default portfolio",
                        "Scheduled briefs cover this portfolio only; empty means every portfolio", () -> null).withNullable(),
                SettingField.of(SECTION, "noResearchMode", SettingType.ENUM, 4, "When there is no new research",
                        "MINIMAL writes a short brief; SKIP only records the period", () -> properties.noResearchMode().name())
                        .withOptions(List.of("MINIMAL", "SKIP")),
                SettingField.integer("ai", "maxAiBriefsPerDay", 1, "Daily brief generation limit",
                        "Briefs per day whose AI part is generated; later briefs are deterministic only", () -> maxAiBriefsPerDay, 0, 50));
    }

    @Override
    public void validate(String section, Map<String, Object> values, Map<String, String> errors) {
        if (!SECTION.equals(section) || !(values.get("defaultPortfolioId") instanceof String id)) {
            return;
        }
        try {
            portfolios.portfolio(UUID.fromString(id));
        } catch (ApiException | IllegalArgumentException e) {
            errors.put("defaultPortfolioId", "is not an existing portfolio");
        }
    }
}

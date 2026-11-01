package com.investary.knowledge.usage;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.investary.common.settings.RuntimeSettings;
import com.investary.common.settings.SettingField;
import com.investary.common.settings.SettingsChangedEvent;
import com.investary.common.settings.SettingsContributor;
import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.AiLimitsProperties;

/**
 * AI limits owned by the knowledge module: concurrent chat and embedding requests (applied to
 * {@link AiConcurrencyLimits} as soon as they are saved) and the optional daily token budget of automatic calls.
 */
@Component
public class AiSettings implements SettingsContributor {

    public static final String SECTION = "ai";

    private final AiLimitsProperties properties;
    private final RuntimeSettings settings;
    private final AiConcurrencyLimits limits;
    private final AiUsageService usage;

    public AiSettings(AiLimitsProperties properties, RuntimeSettings settings, AiConcurrencyLimits limits, AiUsageService usage) {
        this.properties = properties;
        this.settings = settings;
        this.limits = limits;
        this.usage = usage;
    }

    public int maxConcurrentChat() {
        return settings.integer(SECTION, "maxConcurrentChat", properties.maxConcurrentChat());
    }

    public int maxConcurrentEmbedding() {
        return settings.integer(SECTION, "maxConcurrentEmbedding", properties.maxConcurrentEmbedding());
    }

    /** Daily budget of provider-reported tokens for automatic calls; empty when there is no budget. */
    public Optional<Long> dailyAutomaticTokenBudget() {
        return settings.number(SECTION, "dailyAutomaticTokenBudget");
    }

    /** True when a budget is set and today's automatic calls have used it up. */
    public boolean automaticTokenBudgetReached() {
        return dailyAutomaticTokenBudget().map(budget -> usage.automaticTokensToday() >= budget).orElse(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applyAtStartup() {
        apply();
    }

    @EventListener
    public void onChanged(SettingsChangedEvent event) {
        if (SECTION.equals(event.section())) {
            apply();
        }
    }

    private void apply() {
        limits.resize(maxConcurrentChat(), maxConcurrentEmbedding());
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.integer(SECTION, "maxConcurrentChat", 20, "Max concurrent AI (chat) requests", "Whole application",
                        properties::maxConcurrentChat, 1, 8),
                SettingField.integer(SECTION, "maxConcurrentEmbedding", 21, "Max concurrent embedding requests", "Whole application",
                        properties::maxConcurrentEmbedding, 1, 4),
                SettingField.integer(SECTION, "dailyAutomaticTokenBudget", 30, "Daily token budget for automatic AI calls",
                        "Provider-reported tokens of automatic assessments and briefs per day; empty means no budget", () -> null,
                        1_000, 10_000_000).withNullable());
    }
}

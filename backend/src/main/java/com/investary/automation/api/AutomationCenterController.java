package com.investary.automation.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.investary.automation.application.center.AiUsageReportService;
import com.investary.automation.application.center.AiUsageReportService.UsageView;
import com.investary.automation.application.center.AutomationOverviewService;
import com.investary.automation.application.center.AutomationOverviewService.OverviewView;
import com.investary.common.settings.RuntimeSettings;
import com.investary.common.settings.RuntimeSettings.SectionView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * Automation Center (Phase 5 / Milestone 4): overview, runtime settings and AI usage. Local-only, like the
 * rest of {@code /api/v1/automation} ({@link LocalOnlyRequestFilter}). The settings contain no credentials.
 */
@RestController
@RequestMapping("/api/v1/automation")
public class AutomationCenterController {

    private final AutomationOverviewService overview;
    private final AiUsageReportService usage;
    private final RuntimeSettings settings;

    public AutomationCenterController(AutomationOverviewService overview, AiUsageReportService usage, RuntimeSettings settings) {
        this.overview = overview;
        this.usage = usage;
        this.settings = settings;
    }

    /**
     * @param period metrics window: {@code 24h}, {@code 7d} or {@code 30d}
     */
    @GetMapping("/overview")
    public OverviewView overview(@RequestParam(defaultValue = "24h") String period) {
        return overview.overview(period);
    }

    /**
     * @param period {@code day} or {@code week} buckets
     * @param days   days back from today, 1–90
     */
    @GetMapping("/usage")
    public UsageView usage(@RequestParam(defaultValue = "day") String period, @RequestParam(defaultValue = "7") int days) {
        return usage.report(period, days);
    }

    @GetMapping("/settings")
    public List<SectionView> settings() {
        return settings.sections();
    }

    @GetMapping("/settings/{section}")
    public SectionView section(@PathVariable String section) {
        return settings.section(section);
    }

    /** Saves the given fields of one section; a null value resets that field to its default. */
    @PutMapping("/settings/{section}")
    public SectionView update(@PathVariable String section, @Valid @RequestBody UpdateSettingsRequest request) {
        return settings.update(section, request.version(), request.values());
    }

    @DeleteMapping("/settings/{section}/{field}")
    public SectionView reset(@PathVariable String section, @PathVariable String field) {
        return settings.reset(section, field);
    }

    /**
     * @param version the section version that was read; a concurrent change makes the save fail with 409
     */
    public record UpdateSettingsRequest(@NotNull @Min(0) Integer version, @NotNull Map<String, JsonNode> values) {
    }
}

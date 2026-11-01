package com.investary.automation.application.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.automation.domain.NotificationEntity;
import com.investary.automation.domain.NotificationPriority;
import com.investary.automation.domain.NotificationType;
import com.investary.automation.persistence.NotificationStore.NewRow;
import com.investary.intelligence.application.BriefGeneratedEvent;
import com.investary.intelligence.application.ImpactAssessmentCreatedEvent;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.ImpactCategory;
import com.investary.intelligence.domain.ResearchState;
import com.investary.knowledge.assistant.EvidenceStatus;

class NotificationRulesTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    @Test
    void portfolioResearchNeedsADirectMatchAndIsKeyedBySourceAndPortfolio() {
        assertThat(NotificationRules.portfolioResearch(NotificationEntity.EMAIL, SOURCE, "IMP Q3", PORTFOLIO, "Main", List.of())).isEmpty();
        NewRow row = NotificationRules.portfolioResearch(NotificationEntity.EMAIL, SOURCE, "IMP Q3", PORTFOLIO, "Main", List.of("IMP", "VNM"))
                .orElseThrow();
        assertThat(row.type()).isEqualTo(NotificationType.PORTFOLIO_RESEARCH);
        assertThat(row.title()).isEqualTo("New research on IMP, VNM");
        assertThat(row.dedupKey()).isEqualTo("portfolio-research:" + SOURCE + ":" + PORTFOLIO);
    }

    @Test
    void newResearchIsOneLowPrioritySummaryPerRun() {
        UUID run = UUID.randomUUID();
        assertThat(NotificationRules.newResearch(run, 0)).isEmpty();
        NewRow row = NotificationRules.newResearch(run, 3).orElseThrow();
        assertThat(row.priority()).isEqualTo(NotificationPriority.LOW);
        assertThat(row.title()).isEqualTo("3 new research emails");
        assertThat(row.dedupKey()).isEqualTo("new-research:" + run);
    }

    @Test
    void onlyValidatedSupportedRisksOfDirectHoldingsAreMaterial() {
        UUID instrument = UUID.randomUUID();
        // The category comes from the validated assessment; promotional wording in an email can never set it.
        List<ImpactAssessmentCreatedEvent.Item> items = List.of(
                item(instrument, "IMP", ImpactCategory.DIRECT_HOLDING, ImpactCategory.POTENTIAL_RISK, EvidenceStatus.SUPPORTED),
                item(UUID.randomUUID(), "VNM", ImpactCategory.DIRECT_HOLDING, ImpactCategory.POTENTIAL_RISK, EvidenceStatus.INSUFFICIENT),
                item(UUID.randomUUID(), "FPT", ImpactCategory.MONITORING_ONLY, ImpactCategory.POTENTIAL_RISK, EvidenceStatus.SUPPORTED),
                item(UUID.randomUUID(), "HPG", ImpactCategory.DIRECT_HOLDING, ImpactCategory.POTENTIAL_OPPORTUNITY, EvidenceStatus.SUPPORTED));
        UUID assessment = UUID.randomUUID();
        List<NewRow> rows = NotificationRules.risks(new ImpactAssessmentCreatedEvent(assessment, PORTFOLIO, "EMAIL", SOURCE, items), "Main",
                "Mua ngay! Cơ hội cuối cùng!");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.title()).isEqualTo("Possible risk for IMP");
            assertThat(row.priority()).isEqualTo(NotificationPriority.HIGH);
            assertThat(row.entityType()).isEqualTo(NotificationEntity.ASSESSMENT);
            assertThat(row.message()).contains("not a trading signal");
            assertThat(row.dedupKey()).isEqualTo("risk:" + PORTFOLIO + ":" + instrument + ":" + SOURCE);
        });
        assertThat(NotificationRules.risks(new ImpactAssessmentCreatedEvent(assessment, PORTFOLIO, null, null, items), "Main", null)).isEmpty();
    }

    @Test
    void conflictsNotifyForHeldSymbolsWithAStableKey() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BriefGeneratedEvent first = brief(BriefTrigger.SCHEDULED, BriefStatus.COMPLETED, List.of(
                new BriefGeneratedEvent.Conflict("IMP", true, List.of(a, b), List.of("SSI", "VND")),
                new BriefGeneratedEvent.Conflict("HPG", false, List.of(a, b), List.of("SSI", "VND")),
                new BriefGeneratedEvent.Conflict("VNM", true, List.of(a), List.of("SSI"))));
        BriefGeneratedEvent reordered = brief(BriefTrigger.MANUAL, BriefStatus.COMPLETED, List.of(
                new BriefGeneratedEvent.Conflict("IMP", true, List.of(b, a), List.of("VND", "SSI"))));
        List<NewRow> rows = NotificationRules.conflicts(first, "Main");
        assertThat(rows).extracting(NewRow::title).containsExactly("Different views on IMP");
        assertThat(NotificationRules.conflicts(reordered, "Main").getFirst().dedupKey()).isEqualTo(rows.getFirst().dedupKey());
    }

    @Test
    void briefReadySkipsRegenerationsAndSkippedBriefs() {
        assertThat(NotificationRules.briefReady(brief(BriefTrigger.REGENERATION, BriefStatus.COMPLETED, List.of()), "Main")).isEmpty();
        assertThat(NotificationRules.briefReady(brief(BriefTrigger.SCHEDULED, BriefStatus.SKIPPED, List.of()), "Main")).isEmpty();
        NewRow row = NotificationRules.briefReady(brief(BriefTrigger.SCHEDULED, BriefStatus.PARTIAL, List.of()), "Main").orElseThrow();
        assertThat(row.priority()).isEqualTo(NotificationPriority.NORMAL);
        assertThat(row.message()).contains("without AI");
        assertThat(row.dedupKey()).isEqualTo("brief-ready:" + PORTFOLIO + ":2026-11-01T01:00:00Z");
    }

    @Test
    void syncFailuresNeedAStreakUnlessReconnectIsRequired() {
        UUID account = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        assertThat(NotificationRules.syncFailed(account, List.of(newest, middle), false, "GOOGLE_UNAVAILABLE")).isEmpty();
        assertThat(NotificationRules.syncFailed(account, List.of(newest, middle, oldest), false, "GOOGLE_UNAVAILABLE").orElseThrow().dedupKey())
                .isEqualTo("sync-failed:" + account + ":" + oldest);
        assertThat(NotificationRules.syncFailed(account, List.of(newest), true, "RECONNECT_REQUIRED").orElseThrow().title())
                .isEqualTo("Reconnect Gmail");
        assertThat(NotificationRules.syncFailed(account, List.of(), true, null)).isEmpty();
    }

    @Test
    void failuresAreKeyedByJob() {
        UUID workflow = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        assertThat(NotificationRules.workflowFailed(workflow, job, "INDEX", "INDEXING_FAILED", null).orElseThrow().dedupKey())
                .isEqualTo("workflow-failed:" + workflow + ":" + job);
        assertThat(NotificationRules.briefJobFailed(job, PORTFOLIO, "2026-11-01", "AI_TIMEOUT").orElseThrow().entityType())
                .isEqualTo(NotificationEntity.BRIEF_JOB);
    }

    private static ImpactAssessmentCreatedEvent.Item item(UUID instrument, String symbol, ImpactCategory deterministic, ImpactCategory category,
                                                          EvidenceStatus status) {
        return new ImpactAssessmentCreatedEvent.Item(instrument, symbol, deterministic, category, status);
    }

    private static BriefGeneratedEvent brief(BriefTrigger trigger, BriefStatus status, List<BriefGeneratedEvent.Conflict> conflicts) {
        UUID id = UUID.randomUUID();
        return new BriefGeneratedEvent(id, id, 1, PORTFOLIO, trigger, status, ResearchState.NEW_RESEARCH, Instant.parse("2026-11-01T01:00:00Z"),
                LocalDate.of(2026, 11, 1), conflicts);
    }
}

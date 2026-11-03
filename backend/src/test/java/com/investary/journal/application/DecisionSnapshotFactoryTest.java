package com.investary.journal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.intelligence.IntelligenceTestData;
import com.investary.investor.application.GoalView;
import com.investary.investor.application.ProfileView;
import com.investary.investor.domain.GoalPriority;
import com.investary.investor.domain.InvestmentHorizon;
import com.investary.investor.domain.RiskTolerance;
import com.investary.journal.domain.DecisionContent;
import com.investary.journal.domain.DecisionType;
import com.investary.portfolio.application.PortfolioViews.PriceView;

class DecisionSnapshotFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");

    @Test
    void copiesHoldingsAndRecordsMissingPricesWithoutEstimating() {
        DecisionSnapshot snapshot = DecisionSnapshotFactory.build(NOW, IntelligenceTestData.TODAY, IntelligenceTestData.snapshot(), null,
                IntelligenceTestData.FPT, "FPT", null, ProfileView.EMPTY, List.of(), null);

        assertThat(snapshot.valuationDate()).isEqualTo(IntelligenceTestData.TODAY);
        assertThat(snapshot.portfolio().holdings()).hasSize(3);
        assertThat(snapshot.portfolio().holdings()).allSatisfy(h -> {
            assertThat(h.marketValue()).isNull();
            assertThat(h.latestPrice()).isNull();
        });
        assertThat(snapshot.portfolio().totalMarketValue()).isNull();
        assertThat(snapshot.missingPrices()).containsExactly("FPT", "SCS", "VNM");
        assertThat(snapshot.position().held()).isTrue();
        assertThat(snapshot.position().quantity()).isEqualByComparingTo("600");
        assertThat(snapshot.position().costWeightPct()).isEqualByComparingTo("60.00");
        assertThat(snapshot.limitations()).anySatisfy(l -> assertThat(l).contains("FPT, SCS, VNM").contains("not estimated"));
        assertThat(snapshot.limitations()).anySatisfy(l -> assertThat(l).contains("investor profile"));
    }

    @Test
    void watchlistDecisionRecordsTheInvestorAndTheLatestPriceObservationOnly() {
        UUID instrument = UUID.randomUUID();
        PriceView price = new PriceView(UUID.randomUUID(), instrument, new BigDecimal("25000"), LocalDate.parse("2026-10-28"), "Bảng giá",
                null, NOW);
        ProfileView profile = new ProfileView(true, null, RiskTolerance.LOW, "Bảo toàn vốn", InvestmentHorizon.MEDIUM, List.of(), null,
                null, null, NOW);
        GoalView goal = new GoalView(UUID.randomUUID(), "Mua nhà", null, new BigDecimal("3000000000"), LocalDate.parse("2030-01-01"), "VND",
                GoalPriority.HIGH, null, NOW, NOW);

        DecisionSnapshot snapshot = DecisionSnapshotFactory.build(NOW, IntelligenceTestData.TODAY, null, null, instrument, "HPG", price,
                profile, List.of(goal), null);

        assertThat(snapshot.portfolio()).isNull();
        assertThat(snapshot.position().held()).isFalse();
        assertThat(snapshot.position().latestPrice()).isEqualByComparingTo("25000");
        assertThat(snapshot.position().marketValue()).isNull();
        assertThat(snapshot.investor().riskTolerance()).isEqualTo("LOW");
        assertThat(snapshot.investor().goals()).extracting(DecisionSnapshot.Goal::name).containsExactly("Mua nhà");
        assertThat(snapshot.limitations()).anySatisfy(l -> assertThat(l).startsWith("Watchlist decision"));
    }

    @Test
    void diffComparesNumbersByValueAndListsOnlyChangedFields() {
        DecisionContent before = new DecisionContent(null, null, null, "FPT", DecisionType.BUY, IntelligenceTestData.TODAY, "A", null,
                null, null, null, null, null, null, null, new BigDecimal("10.0"), null, null);
        DecisionContent after = new DecisionContent(null, null, null, "FPT", DecisionType.BUY, IntelligenceTestData.TODAY, "B", "Lý do",
                null, null, null, null, null, null, null, new BigDecimal("10"), null, null);

        Map<String, Object> diff = DecisionService.diff(before.asMap(), after.asMap());

        assertThat(diff).containsOnlyKeys("title", "rationale");
        assertThat(diff.get("title")).isEqualTo(Map.of("from", "A", "to", "B"));
    }
}

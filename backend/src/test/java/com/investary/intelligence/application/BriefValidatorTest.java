package com.investary.intelligence.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.intelligence.application.BriefEvidenceCollector.Entry;
import com.investary.intelligence.application.BriefValidator.Review;
import com.investary.intelligence.domain.BriefItemKind;
import com.investary.intelligence.domain.Relevance;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ResearchAvailabilityService.AvailableSource;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchHit.MatchType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BriefValidatorTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static final Map<String, String> SOURCES = Map.of(
            "S1", "FPT: lợi nhuận quý 3 tăng 25% nhờ mảng công nghệ. Khuyến nghị: MUA.",
            "S2", "HPG: sản lượng thép tăng 10% so với cùng kỳ.",
            "S3", "Thị trường: thanh khoản giảm, nhà đầu tư thận trọng.");

    private Review review(String reply) {
        JsonNode node = json.readTree(reply);
        return BriefValidator.review(node, Map.of("FPT", List.of("S1"), "HPG", List.of("S2")), Map.of("VNM", List.of()), List.of("S3"),
                SOURCES, "Danh mục: Thử nghiệm\n");
    }

    @Test
    void inventedSourceIdsAreRemovedAndLowerTheStatus() {
        Review review = review("""
                {"executiveSummary": "Lợi nhuận FPT tăng 25% [S1]. Có thêm thông tin quan trọng [S9]."}""");

        assertThat(review.summary().text()).doesNotContain("S9");
        assertThat(review.summary().citations()).containsExactly("S1");
        assertThat(review.summary().evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(review.summary().kind()).isEqualTo(BriefItemKind.AI_INTERPRETATION);
        assertThat(review.warnings()).anyMatch(w -> w.contains("S9"));
    }

    @Test
    void findingsMayOnlyCiteTheirOwnHoldingsEvidence() {
        Review review = review("""
                {"portfolioFindings": [
                  {"symbol": "FPT", "finding": "Lợi nhuận FPT tăng 25% nhờ mảng công nghệ [S1]."},
                  {"symbol": "HPG", "finding": "Sản lượng thép HPG tăng [S1]."},
                  {"symbol": "MWG", "finding": "MWG mở rộng cửa hàng [S2]."}]}""");

        assertThat(review.findings()).containsOnlyKeys("FPT");
        assertThat(review.findings().get("FPT").getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(review.warnings()).anyMatch(w -> w.contains("MWG")).anyMatch(w -> w.startsWith("HPG"));
    }

    @Test
    void unsupportedFiguresLowerTheStatus() {
        Review review = review("""
                {"portfolioFindings": [{"symbol": "FPT", "finding": "Lợi nhuận FPT tăng 40% trong quý 3 [S1]."}]}""");

        assertThat(review.findings().get("FPT").getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(review.warnings()).anyMatch(w -> w.contains("40"));
    }

    @Test
    void uncitedClaimsAreDropped() {
        Review review = review("""
                {"executiveSummary": "Thị trường sẽ tăng mạnh trong tuần tới.", "risks": ["Rủi ro lãi suất tăng."]}""");

        assertThat(review.summary()).isNull();
        assertThat(review.risks()).isEmpty();
    }

    @Test
    void tradingInstructionsAreNeverKept() {
        Review review = review("""
                {"executiveSummary": "Nên mua thêm FPT vì lợi nhuận tăng [S1].",
                 "followUps": ["Đặt lệnh mua FPT ngay", "Đọc kỹ báo cáo FPT [S1]", "Chốt lời HPG"]}""");

        assertThat(review.summary()).isNull();
        assertThat(review.followUps()).extracting(i -> i.text()).containsExactly("Đọc kỹ báo cáo FPT");
    }

    @Test
    void marketOverviewWithoutMarketSourcesIsIgnored() {
        JsonNode node = json.readTree("""
                {"marketOverview": "Thanh khoản giảm [S3]."}""");
        Review review = BriefValidator.review(node, Map.of(), Map.of(), List.of(), SOURCES, "");

        assertThat(review.market()).isNull();
        assertThat(review.warnings()).isNotEmpty();
    }

    @Test
    void conflictingRatingsOfDifferentBrokeragesAreDetected() {
        Entry tcbs = entry("TCBS", "FPT", "S1", "Báo cáo FPT. Khuyến nghị: MUA với giá mục tiêu 150.000.");
        Entry ssi = entry("SSI", "FPT", "S2", "FPT cập nhật. Khuyến nghị: Kém khả quan do định giá cao.");
        Entry vnd = entry("VNDIRECT", "FPT", "S3", "FPT. Rating: Outperform.");
        Entry hpg = entry("TCBS", "HPG", "S4", "HPG. Khuyến nghị: MUA.");

        var conflicts = BriefConflicts.detect(List.of(tcbs, ssi, vnd, hpg));

        assertThat(conflicts).containsOnlyKeys("FPT");
        assertThat(conflicts.get("FPT")).extracting(v -> v.rating().label()).containsExactly("Mua", "Kém khả quan", "Outperform");
        assertThat(BriefConflicts.detect(List.of(tcbs, vnd))).isEmpty();
        assertThat(BriefConflicts.rating("Khuyến nghị CHỜ MUA").orElseThrow().label()).isEqualTo("Chờ mua");
    }

    static Entry entry(String brokerage, String ticker, String label, String text) {
        UUID sourceId = UUID.randomUUID();
        AvailableSource source = new AvailableSource(UUID.randomUUID(), KnowledgeSourceType.DOCUMENT, sourceId, ticker + " report", brokerage,
                LocalDate.parse("2026-10-30"), "PUBLISHED", null, Instant.parse("2026-10-31T02:00:00Z"),
                Instant.parse("2026-10-31T03:00:00Z"), ticker, "STOCK_ANALYSIS", List.of(ticker), "a".repeat(64));
        SearchHit hit = new SearchHit(UUID.randomUUID(), source.knowledgeSourceId(), KnowledgeSourceType.DOCUMENT, sourceId, source.title(),
                brokerage, source.publishedOn(), "PUBLISHED", 1, 1, null, 0, text, List.of(ticker), ticker, null, null, null, null, 0,
                EnumSet.noneOf(MatchType.class));
        return new Entry(source, Relevance.DIRECT, List.of(ticker), List.of(), List.of(new LabeledHit(label, hit)), List.of(), false, false);
    }
}

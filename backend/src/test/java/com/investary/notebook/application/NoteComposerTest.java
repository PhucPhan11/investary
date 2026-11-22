package com.investary.notebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceState;
import com.investary.knowledge.search.SearchHit;
import com.investary.notebook.application.NoteComposer.Composition;
import com.investary.notebook.application.NoteComposer.FaqContent;
import com.investary.notebook.application.NoteComposer.Rating;
import com.investary.notebook.application.NoteComposer.SummaryContent;
import com.investary.notebook.application.NoteComposer.ViewsContent;
import com.investary.notebook.application.NoteEvidenceCollector.NoteEvidence;
import com.investary.notebook.application.NoteEvidenceCollector.SourceGroup;
import com.investary.notebook.domain.NoteKind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NoteComposerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final SourceState tcbs = source(KnowledgeSourceType.DOCUMENT, "TCBS", LocalDate.parse("2026-10-20"));
    private final SourceState ssi = source(KnowledgeSourceType.EMAIL, "SSI", LocalDate.parse("2026-10-25"));
    private final NoteEvidence evidence = new NoteEvidence(
            List.of(labeled("S1", tcbs, "Khuyến nghị: Mua. Giá mục tiêu 80.000 đồng/cổ phiếu."),
                    labeled("S2", ssi, "SCS - Bán - Giá mục tiêu 60.000 đồng. Sản lượng hàng hóa dự báo giảm.")),
            List.of(new SourceGroup("G1", tcbs, List.of("S1")), new SourceGroup("G2", ssi, List.of("S2"))),
            List.of("Chỉ dùng 2 trong 3 nguồn phù hợp."));

    @Test
    void summaryKeepsOnlyCitedPointsAndDropsTradingInstructions() {
        Composition result = NoteComposer.compose(NoteKind.SUMMARY, null, node("""
                {"points": [
                  {"text": "TCBS khuyến nghị Mua với giá mục tiêu 80.000 đồng [S1]."},
                  {"text": "Một ý không có nguồn."},
                  {"text": "Nhà đầu tư nên mua SCS ngay [S1]."},
                  {"text": "Một nguồn không tồn tại [S9]."}
                ]}"""), evidence, Map.of());

        SummaryContent content = (SummaryContent) result.content();
        assertThat(content.points()).hasSize(1);
        assertThat(content.points().getFirst().labels()).containsExactly("S1");
        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(result.citedLabels()).containsExactly("S1");
        assertThat(result.limitations()).contains("Chỉ dùng 2 trong 3 nguồn phù hợp.")
                .anyMatch(l -> l.contains("lệnh giao dịch"))
                .anyMatch(l -> l.contains("không có trích dẫn hợp lệ"));
    }

    @Test
    void figuresMissingFromTheCitedSourceLowerTheStatus() {
        Composition result = NoteComposer.compose(NoteKind.SUMMARY, null, node("""
                {"points": [{"text": "TCBS đặt giá mục tiêu 95.000 đồng [S1]."}]}"""), evidence, Map.of());

        assertThat(result.status()).isNotEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void consensusViewsMayCiteOnlyTheirOwnSourceAndRatingsComeFromStoredRecords() {
        Composition result = NoteComposer.compose(NoteKind.CONSENSUS, "SCS", node("""
                {"views": [
                  {"group": "G1", "view": "TCBS khuyến nghị Mua, giá mục tiêu 80.000 đồng [S2]."},
                  {"group": "g2", "view": "SSI khuyến nghị Bán, giá mục tiêu 60.000 đồng [S2]."},
                  {"group": "G7", "view": "Không có nhóm này [S1]."}
                ],
                 "agreements": [],
                 "disagreements": ["TCBS (Mua, 80.000) và SSI (Bán, 60.000) trái ngược nhau [S1, S2]."]}"""),
                evidence, Map.of(ssi.sourceId(), new Rating("Bán", "1. Khuyến nghị cổ phiếu")));

        ViewsContent content = (ViewsContent) result.content();
        assertThat(content.ticker()).isEqualTo("SCS");
        // G1 cited only another source's label, so its view was dropped and it had no stored rating either.
        assertThat(content.sources()).extracting(NoteComposer.SourceEntry::group).containsExactly("G2");
        NoteComposer.SourceEntry entry = content.sources().getFirst();
        assertThat(entry.rating()).isEqualTo("Bán");
        assertThat(entry.brokerage()).isEqualTo("SSI");
        assertThat(entry.dateBasis()).isEqualTo("EMAIL_SENT");
        assertThat(entry.view().labels()).containsExactly("S2");
        assertThat(content.disagreements()).hasSize(1);
        assertThat(result.citedLabels()).containsExactlyInAnyOrder("S1", "S2");
    }

    @Test
    void faqQuestionsLoseMarkersAndUncitedAnswersAreDropped() {
        Composition result = NoteComposer.compose(NoteKind.FAQ, null, node("""
                {"items": [
                  {"question": "Giá mục tiêu của TCBS? [S1]", "answer": "TCBS đặt giá mục tiêu 80.000 đồng [S1]."},
                  {"question": "Câu hỏi không có câu trả lời có nguồn?", "answer": "Không rõ."},
                  {"question": "Có nên bán ra không?", "answer": "Theo SSI, Bán [S2]."}
                ]}"""), evidence, Map.of());

        FaqContent content = (FaqContent) result.content();
        assertThat(content.items()).hasSize(1);
        assertThat(content.items().getFirst().question()).isEqualTo("Giá mục tiêu của TCBS?");
        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void nothingValidMeansInsufficient() {
        Composition result = NoteComposer.compose(NoteKind.SUMMARY, null, node("{\"points\": [\"Không có nguồn.\"]}"), evidence, Map.of());

        assertThat(result.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(((SummaryContent) result.content()).points()).isEmpty();
    }

    @Test
    void timelineIsOldestFirstAndFingerprintedSourcesAreOrdered() {
        List<SourceState> ordered = NoteEvidenceCollector.candidates(List.of(ssi, tcbs), NoteKind.TIMELINE, "SCS");
        assertThat(ordered).containsExactly(tcbs, ssi);
        assertThat(NoteEvidenceCollector.candidates(List.of(tcbs, ssi), NoteKind.CONSENSUS, "SCS")).containsExactly(ssi, tcbs);
        assertThat(NoteEvidenceCollector.candidates(List.of(tcbs, ssi), NoteKind.CONSENSUS, "FPT")).isEmpty();
    }

    @Test
    void groupListIsBuiltFromStoredMetadata() {
        assertThat(NotePrompts.groups(evidence.groups())).isEqualTo("G1: S1 (TCBS, 2026-10-20)\nG2: S2 (SSI, 2026-10-25)\n");
    }

    private JsonNode node(String value) {
        return json.readTree(value);
    }

    private static SourceState source(KnowledgeSourceType type, String brokerage, LocalDate published) {
        return new SourceState(type, UUID.randomUUID(), UUID.randomUUID(), brokerage + " SCS", brokerage, published,
                type == KnowledgeSourceType.EMAIL ? "EMAIL_SENT" : "PUBLISHED",
                type == KnowledgeSourceType.EMAIL ? Instant.parse("2026-10-25T02:00:00Z") : null, Instant.parse("2026-10-26T00:00:00Z"),
                "SCS", List.of("SCS"), "INDEXED", "sha-" + brokerage);
    }

    private static LabeledHit labeled(String label, SourceState source, String content) {
        return new LabeledHit(label, new SearchHit(UUID.randomUUID(), source.knowledgeSourceId(), source.sourceType(), source.sourceId(),
                source.title(), source.brokerage(), source.publishedOn(), source.dateBasis(), 1, 1, null, 0, content, List.of("SCS"), "SCS",
                null, null, source.sentAt(), null, 0, EnumSet.noneOf(SearchHit.MatchType.class)));
    }
}

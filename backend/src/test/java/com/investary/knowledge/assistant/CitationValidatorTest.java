package com.investary.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    private static final Map<String, String> SOURCES = Map.of(
            "S1", "SCS - Chờ mua - Trong quý 3/2026, SCS ghi nhận doanh thu ổn định; giá mục tiêu 78.500 đồng.",
            "S2", "SSI khuyến nghị Bán SCS do rủi ro cạnh tranh từ sân bay Long Thành.");

    @Test
    void validAnswerKeepsItsStatusAndCitationOrder() {
        var result = CitationValidator.validate(
                "Theo TCBS, SCS ở trạng thái Chờ mua với giá mục tiêu 78.500 đồng [S1]. SSI lại khuyến nghị Bán [S2].",
                EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(result.citedLabels()).containsExactly("S1", "S2");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void inventedCitationIsRemovedAndDowngradesTheAnswer() {
        var result = CitationValidator.validate("SCS được khuyến nghị Chờ mua [S1, S9]. Doanh thu tăng mạnh [S7].",
                EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.answer()).contains("[S1]").doesNotContain("S9").doesNotContain("S7");
        assertThat(result.citedLabels()).containsExactly("S1");
        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("S9").contains("S7"));
    }

    @Test
    void figureNotInTheCitedSourceIsFlagged() {
        var result = CitationValidator.validate("Giá mục tiêu của SCS là 95.000 đồng [S1].", EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("95000").contains("S1"));
    }

    @Test
    void figureWrittenWithOtherSeparatorsStillMatches() {
        var result = CitationValidator.validate("Giá mục tiêu 78,500 đồng [S1].", EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void uncitedFigureIsReported() {
        var result = CitationValidator.validate("SCS Chờ mua [S1]. Lợi nhuận dự kiến tăng 25%.", EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("không có trích dẫn"));
    }

    @Test
    void answerWithoutCitationsIsInsufficient() {
        var result = CitationValidator.validate("SCS là cổ phiếu tốt nên mua.", EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(result.citedLabels()).isEmpty();
    }

    @Test
    void sentenceUnrelatedToItsSourceIsFlagged() {
        var result = CitationValidator.validate("Ngân hàng Vietcombank tăng trưởng tín dụng vượt kỳ vọng [S1].",
                EvidenceStatus.SUPPORTED, SOURCES, "ngân hàng?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void markerAfterTheFullStopStaysWithItsSentence() {
        var result = CitationValidator.validate("Giá mục tiêu 78.500 đồng. [S1]", EvidenceStatus.SUPPORTED, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(result.citedLabels()).containsExactly("S1");
    }

    @Test
    void modelStatusIsNeverRaised() {
        var result = CitationValidator.validate("SCS ở trạng thái Chờ mua [S1].", EvidenceStatus.PARTIAL, SOURCES, "SCS?");

        assertThat(result.status()).isEqualTo(EvidenceStatus.PARTIAL);
    }
}

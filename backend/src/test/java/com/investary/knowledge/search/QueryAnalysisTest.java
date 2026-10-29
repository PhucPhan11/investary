package com.investary.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QueryAnalysisTest {

    private static final List<String> BROKERAGES = List.of("TCBS", "SSI", "VNDIRECT", "VCBS");

    @Test
    void separatesBrokeragesFromTickers() {
        QueryAnalysis analysis = QueryAnalysis.of("So sánh nhận định của TCBS và SSI về FPT.", BROKERAGES);

        assertThat(analysis.brokerages()).containsExactly("TCBS", "SSI");
        assertThat(analysis.tickers()).containsExactly("FPT");
    }

    @Test
    void foldsAccentsAndDropsStopWords() {
        QueryAnalysis analysis = QueryAnalysis.of("Báo cáo nào đề cập đến tác động của sân bay Long Thành?", BROKERAGES);

        assertThat(analysis.terms()).contains("long", "thanh", "san", "bay", "tac", "dong").doesNotContain("nao", "cua", "bao", "cao");
        assertThat(analysis.lexicalQuery()).contains(" or ");
    }

    @Test
    void coverageCountsTermsInFoldedText() {
        QueryAnalysis analysis = QueryAnalysis.of("sân bay Long Thành", BROKERAGES);

        assertThat(analysis.coverage("canh tranh tu san bay long thanh")).isEqualTo(1.0);
        assertThat(analysis.coverage("doanh thu on dinh")).isZero();
    }

    @Test
    void brokerageNameInsideAWordIsNotAMatch() {
        assertThat(QueryAnalysis.of("Tin tức về SSIX", BROKERAGES).brokerages()).isEmpty();
    }
}

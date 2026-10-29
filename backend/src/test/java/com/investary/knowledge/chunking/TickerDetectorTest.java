package com.investary.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TickerDetectorTest {

    @Test
    void findsTickersNamedInText() {
        assertThat(TickerDetector.detect("SCS - Chờ mua. FPT và MWG tăng; VN30 giảm.")).containsExactly("SCS", "FPT", "MWG");
    }

    @Test
    void ignoresCurrenciesIndicatorsAndRatings() {
        assertThat(TickerDetector.detect("GDP tăng, CPI ổn định, tỷ giá USD/VND, khuyến nghị MUA, ETF mới")).isEmpty();
    }

    @Test
    void ignoresUpperCaseHeadings() {
        assertThat(TickerDetector.detect("TIN TỨC THỊ TRƯỜNG CHỨNG KHOÁN HÔM NAY\nHPG tăng trần")).containsExactly("HPG");
    }

    @Test
    void doesNotMatchPartsOfLongerWords() {
        assertThat(TickerDetector.detect("VNINDEX, TCBS, HOSE, Ảnh hưởng, ABCD")).isEmpty();
    }
}

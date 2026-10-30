package com.investary.intelligence.application;

import static com.investary.intelligence.IntelligenceTestData.TODAY;
import static com.investary.intelligence.IntelligenceTestData.hit;
import static com.investary.intelligence.IntelligenceTestData.instrument;
import static com.investary.intelligence.IntelligenceTestData.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.investary.intelligence.IntelligenceTestData;
import com.investary.intelligence.application.ResearchMatcher.HoldingResearch;
import com.investary.intelligence.application.ResearchMatcher.MatchKind;
import com.investary.intelligence.application.ResearchMatcher.PortfolioResearch;
import com.investary.intelligence.application.ResearchMatcher.ResearchFilters;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchHit;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;

class ResearchMatcherTest {

    private final HybridSearchService search = mock(HybridSearchService.class);
    private final ResearchMatcher matcher = new ResearchMatcher(search, new IntelligenceProperties(90, 30, 10, 3, 3));

    @BeforeEach
    void noResultsByDefault() {
        when(search.search(anyString(), any(), any())).thenReturn(result(List.of()));
    }

    @Test
    void classifiesDirectResearchPassingMentionsAndMarketContext() {
        SearchHit report = hit("FPT - Báo cáo cập nhật", "Khuyến nghị: Khả quan.", List.of(), "FPT", TODAY.minusDays(10), "PUBLISHED");
        SearchHit onlyTicker = hit("Bản tin sáng", "FPT ký hợp đồng mới.", List.of("FPT"), null, TODAY.minusDays(2), "EMAIL_SENT");
        SearchHit newsletter = hit("Bản tin sáng", "FPT, VNM và HPG dẫn dắt thị trường.", List.of("FPT", "VNM", "HPG"), null,
                TODAY.minusDays(1), "EMAIL_SENT");
        SearchHit market = hit("Nhận định thị trường", "VN-Index điều chỉnh nhẹ, thanh khoản thấp.", List.of(), null,
                TODAY.minusDays(3), "EMAIL_SENT");
        when(search.search(anyString(), argThat(f -> f != null && "FPT".equals(f.ticker())), any()))
                .thenReturn(result(List.of(report, onlyTicker, newsletter)));
        when(search.search(eq(ResearchMatcher.MARKET_QUERY), any(), any())).thenReturn(result(List.of(market, newsletter)));

        PortfolioResearch research = matcher.match(List.of(instrument(IntelligenceTestData.FPT, "FPT", "Công ty Cổ phần FPT")),
                ResearchFilters.NONE, TODAY);

        HoldingResearch fpt = research.holdings().getFirst();
        assertThat(fpt.direct()).extracting(e -> e.hit().content()).containsExactly("FPT ký hợp đồng mới.", "Khuyến nghị: Khả quan.");
        assertThat(fpt.mentions()).extracting(e -> e.kind()).containsExactly(MatchKind.MENTION);
        assertThat(fpt.newestEvidenceDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(research.marketContext()).hasSize(1).allSatisfy(e -> assertThat(e.kind()).isEqualTo(MatchKind.MARKET_CONTEXT));
    }

    @Test
    void nameMatchesNeedTheNameInTheTextAndAreMentionsUnlessTheTitleNamesIt() {
        InstrumentView vnm = instrument(IntelligenceTestData.VNM, "VNM", "Vinamilk", "Sữa Việt Nam");
        SearchHit byAlias = hit("Ngành tiêu dùng", "Công ty Sữa Việt Nam tăng giá bán.", List.of(), null, TODAY.minusDays(5), "PUBLISHED");
        SearchHit titled = hit("Vinamilk - cập nhật", "Doanh thu quý 3 tăng.", List.of(), null, TODAY.minusDays(6), "PUBLISHED");
        SearchHit unrelated = hit("Ngành thép", "Sản lượng thép tăng.", List.of(), null, TODAY.minusDays(1), "PUBLISHED");
        when(search.search(anyString(), argThat(f -> f != null && f.ticker() == null), any()))
                .thenReturn(result(List.of(byAlias, titled, unrelated)));

        HoldingResearch research = matcher.match(List.of(vnm), ResearchFilters.NONE, TODAY).holdings().getFirst();

        assertThat(research.direct()).extracting(e -> e.hit().title()).containsExactly("Vinamilk - cập nhật");
        assertThat(research.mentions()).extracting(e -> e.hit().content()).containsExactly("Công ty Sữa Việt Nam tăng giá bán.");
    }

    @Test
    void freshnessUsesTheSourceDateAndFlagsUndatedOrOldEvidence() {
        SearchHit old = hit("FPT cũ", "FPT năm ngoái.", List.of("FPT"), "FPT", TODAY.minusDays(200), "PUBLISHED");
        SearchHit undated = hit("FPT không ngày", "FPT không rõ ngày.", List.of("FPT"), "FPT", null, null);
        SearchHit recent = hit("FPT mới", "FPT tuần này.", List.of("FPT"), "FPT", TODAY.minusDays(7), "PUBLISHED");
        when(search.search(anyString(), argThat(f -> f != null && "FPT".equals(f.ticker())), any()))
                .thenReturn(result(List.of(old, undated, recent)));

        HoldingResearch research = matcher.match(List.of(instrument(IntelligenceTestData.FPT, "FPT", "FPT Corp")),
                ResearchFilters.NONE, TODAY).holdings().getFirst();

        // Newest publication date first; undated last.
        assertThat(research.direct()).extracting(e -> e.hit().title()).containsExactly("FPT mới", "FPT cũ", "FPT không ngày");
        assertThat(research.direct()).extracting(e -> e.outdated()).containsExactly(false, true, true);
        assertThat(research.direct().getFirst().ageDays()).isEqualTo(7);
    }

    @Test
    void filtersArePassedToEverySearch() {
        ResearchFilters filters = new ResearchFilters("SSI", TODAY.minusDays(30), TODAY);
        matcher.match(List.of(instrument(IntelligenceTestData.FPT, "FPT", "FPT Corp")), filters, TODAY);

        org.mockito.Mockito.verify(search, org.mockito.Mockito.times(3))
                .search(anyString(), argThat(f -> "SSI".equals(f.brokerage()) && TODAY.minusDays(30).equals(f.from())), any());
    }

    @Test
    void shortNamesAreNotUsedForMatching() {
        InstrumentView instrument = instrument(IntelligenceTestData.FPT, "ABC", "ABC", "Ab");
        assertThat(ResearchMatcher.names(instrument)).isEmpty();
        SearchHit hit = hit("Báo cáo", "Không liên quan ABCD.", List.of(), null, TODAY, "PUBLISHED");
        assertThat(ResearchMatcher.mentions(hit, "ABC", List.of())).isFalse();
        assertThat(ResearchMatcher.mentions(hit("Báo cáo", "Mã ABC tăng.", List.of(), null, TODAY, "PUBLISHED"), "ABC", List.of()))
                .isTrue();
    }
}

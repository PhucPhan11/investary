package com.investary.research.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.investary.email.TestEmails;
import com.investary.email.parsing.ContentAnalyzer;
import com.investary.email.parsing.EmlParser;
import com.investary.email.parsing.TrackingLinkUnwrapper;
import com.investary.research.application.ResearchSource;
import com.investary.research.extraction.ResearchItemExtractor.ExtractedItem;

class ResearchItemExtractorTest {

    private static List<ExtractedItem> items;

    @BeforeAll
    static void extract() throws Exception {
        var email = new EmlParser().parse(new ByteArrayInputStream(TestEmails.fixture("tcbs-style-newsletter.eml")));
        var content = new ContentAnalyzer(new TrackingLinkUnwrapper()).analyzeHtml(email.htmlBody());
        var source = new ResearchSource(UUID.randomUUID(), "TCBS", Instant.now(),
                content.blocks().stream()
                        .map(b -> new ResearchSource.Block(b.index(), b.text(), b.headingLevel(), b.section(), b.lead(), b.language()))
                        .toList(),
                List.of());
        items = new ResearchItemExtractor().extract(source);
    }

    @Test
    void extractsMarketMacroIndustryAndCompanyItemsWithOriginalText() {
        assertThat(items).filteredOn(i -> i.type() == ResearchItemType.MARKET_UPDATE).extracting(ExtractedItem::title)
                .containsExactly("VN-Index", "Thanh khoản", "VN-Index");
        assertThat(items).filteredOn(i -> i.type() == ResearchItemType.MACRO_UPDATE).extracting(ExtractedItem::title)
                .containsExactly("Trong nước");
        assertThat(items).filteredOn(i -> i.type() == ResearchItemType.INDUSTRY_UPDATE).extracting(ExtractedItem::title)
                .containsExactly("Điện - Xăng dầu");

        ExtractedItem vnIndex = items.getFirst();
        assertThat(vnIndex.content()).startsWith("VN-Index: Tăng 0,50% (+9,00 điểm)");
        assertThat(vnIndex.language()).isEqualTo("vi");
        assertThat(vnIndex.sourceLocation()).contains("2. Cập nhật tin tức thị trường > Thị trường chung");
    }

    @Test
    void companyNewsCarriesTickerFromLabel() {
        assertThat(items).filteredOn(i -> i.type() == ResearchItemType.COMPANY_NEWS)
                .extracting(ExtractedItem::ticker, ExtractedItem::language)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("MWG", "vi"), org.assertj.core.groups.Tuple.tuple("MWG", "en"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bilingualRecommendationBecomesOneItemKeepingTheSourceWording() {
        List<ExtractedItem> recommendations = items.stream().filter(i -> i.type() == ResearchItemType.STOCK_RECOMMENDATION).toList();

        assertThat(recommendations).singleElement().satisfies(r -> {
            assertThat(r.ticker()).isEqualTo("SCS");
            assertThat(r.title()).isEqualTo("SCS - Chờ mua");
            assertThat(r.data()).containsEntry("sourceRecommendation", "Chờ mua");
            assertThat(r.content()).startsWith("SCS - Chờ mua - Trong quý 3/2026");
            Map<String, Object> en = (Map<String, Object>) ((Map<String, Object>) r.data().get("translations")).get("en");
            assertThat(en).containsEntry("sourceRecommendation", "Awaiting Buy");
        });
    }

    @Test
    void ignoresPromotionsResearchListingsAndFooter() {
        assertThat(items).noneMatch(i -> i.content().contains("Margin"));
        assertThat(items).noneMatch(i -> i.content().contains("Báo cáo thị trường trái phiếu"));
        assertThat(items).noneMatch(i -> i.content().contains("support@example.com"));
    }

    @Test
    void neverAddsValuesThatAreNotInTheSource() {
        for (ExtractedItem item : items) {
            for (Map.Entry<String, Object> entry : item.data().entrySet()) {
                if (entry.getValue() instanceof String value && !entry.getKey().equals("note")) {
                    assertThat(item.content()).as(entry.getKey()).contains(value);
                }
            }
        }
    }

    @Test
    void recommendationRequiresKnownRatingPhrase() {
        var source = new ResearchSource(UUID.randomUUID(), null, null, List.of(
                new ResearchSource.Block(0, "3. Khuyến nghị cổ phiếu", 1, "3. Khuyến nghị cổ phiếu", null, "vi"),
                new ResearchSource.Block(1, "ABC - Tin đồn thị trường - nhiều nhà đầu tư quan tâm đến cổ phiếu này.", 0,
                        "3. Khuyến nghị cổ phiếu", "ABC", "vi")),
                List.of());

        assertThat(new ResearchItemExtractor().extract(source)).isEmpty();
    }
}

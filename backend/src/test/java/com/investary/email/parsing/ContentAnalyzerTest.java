package com.investary.email.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.investary.email.TestEmails;

class ContentAnalyzerTest {

    private ContentAnalyzer analyzer;
    private AnalyzedContent content;

    @BeforeEach
    void analyzeFixture() throws Exception {
        analyzer = new ContentAnalyzer(new TrackingLinkUnwrapper());
        ParsedEmail email = new EmlParser().parse(new ByteArrayInputStream(TestEmails.fixture("tcbs-style-newsletter.eml")));
        content = analyzer.analyzeHtml(email.htmlBody());
    }

    @Test
    void unwrapsSesClickTrackingButKeepsOriginalUrl() {
        ExtractedLink link = link("IMP - Cập nhật KQKD Q3/2026");

        assertThat(link.originalUrl()).startsWith("https://abc123.r.ap-southeast-1.awstrack.me/L0/");
        assertThat(link.targetUrl()).isEqualTo(
                "https://www.tcbs.com.vn/wp-content/uploads/2026/10/20261028_IMP_Bao_cao_cap_nhat_KQKD_Q3_2026.pdf?utm_source=email");
        assertThat(link.host()).isEqualTo("www.tcbs.com.vn");
    }

    @Test
    void deduplicatesLinksByTargetAndPrefersTextOverEmptyImageLinks() {
        List<ExtractedLink> margin = content.links().stream().filter(l -> l.targetUrl().contains("mo-khoa-uu-dai-margin")).toList();

        assertThat(margin).singleElement().satisfies(l -> assertThat(l.displayText()).isEqualTo("Giảm lãi suất Margin"));
    }

    @Test
    void dropsAnchorsAndNonHttpSchemes() {
        assertThat(content.links()).noneMatch(l -> l.originalUrl().startsWith("#"));
        assertThat(content.links()).noneMatch(l -> l.originalUrl().startsWith("javascript:"));
        assertThat(content.links()).noneMatch(l -> l.originalUrl().startsWith("mailto:"));
        assertThat(content.links()).allMatch(l -> l.targetUrl().startsWith("http"));
    }

    @Test
    void usesImageAltTextForImageOnlyLinks() {
        assertThat(link("Facebook").targetUrl()).isEqualTo("http://www.facebook.com/example");
    }

    @Test
    void recordsSectionAndLanguageOfEachLink() {
        assertThat(link("VIC").section()).isEqualTo("2. Cập nhật tin tức thị trường > Thị trường chung");
        assertThat(link("VIC").language()).isEqualTo("vi");
        assertThat(link("IMP - Q3/2026 Earnings Update").section()).isEqualTo("1. Latest Research Publication");
        assertThat(link("IMP - Q3/2026 Earnings Update").language()).isEqualTo("en");
    }

    @Test
    void buildsHeadingHierarchyAndLeads() {
        ContentBlock vnIndex = content.blocks().stream().filter(b -> b.text().startsWith("VN-Index: Tăng")).findFirst().orElseThrow();

        assertThat(vnIndex.isHeading()).isFalse();
        assertThat(vnIndex.lead()).isEqualTo("VN-Index:");
        assertThat(vnIndex.section()).isEqualTo("2. Cập nhật tin tức thị trường > Thị trường chung");
        assertThat(content.blocks()).filteredOn(b -> b.headingLevel() == 1).extracting(ContentBlock::text)
                .contains("1. Báo cáo phân tích mới", "3. Khuyến nghị cổ phiếu", "1. Latest Research Publication", "3. Recommendation");
    }

    @Test
    void normalizedTextKeepsFiguresAndDropsInvisibleFillerAndScripts() {
        assertThat(content.normalizedText())
                .contains("Tăng 0,50% (+9,00 điểm), đóng cửa tại 1.800,00 điểm")
                .contains("Rose 0.50% (+9.00 points)")
                .doesNotContain("͏", "‌", "­", "should never run");
    }

    @Test
    void detectsVietnameseAsMainLanguageAndEnglishSecond() {
        assertThat(content.languages()).containsExactly("vi", "en");
    }

    @Test
    void extractsLinksAndHeadingsFromPlainText() {
        AnalyzedContent plain = analyzer.analyzePlainText("""
                1. BÁO CÁO MỚI

                Báo cáo ngành ngân hàng: https://www.ssi.com.vn/files/bank-report.pdf.
                Xem thêm <https://www.ssi.com.vn/research>

                VN-Index: Tăng 0,5%.
                """);

        assertThat(plain.blocks().getFirst().headingLevel()).isEqualTo(1);
        assertThat(plain.links()).extracting(ExtractedLink::targetUrl)
                .containsExactly("https://www.ssi.com.vn/files/bank-report.pdf", "https://www.ssi.com.vn/research");
        assertThat(plain.links().getFirst().displayText()).isEqualTo("Báo cáo ngành ngân hàng");
        assertThat(plain.blocks()).anyMatch(b -> "VN-Index:".equals(b.lead()));
    }

    private ExtractedLink link(String text) {
        return content.links().stream().filter(l -> text.equals(l.displayText())).findFirst().orElseThrow();
    }
}

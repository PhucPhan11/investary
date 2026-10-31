package com.investary.research.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.investary.email.TestEmails;
import com.investary.email.parsing.ContentAnalyzer;
import com.investary.email.parsing.EmlParser;
import com.investary.email.parsing.ExtractedLink;
import com.investary.email.parsing.TrackingLinkUnwrapper;

class LinkClassifierTest {

    private static List<ExtractedLink> fixtureLinks;
    private final LinkClassifier classifier = new LinkClassifier();

    @BeforeAll
    static void parseFixture() throws Exception {
        var email = new EmlParser().parse(new ByteArrayInputStream(TestEmails.fixture("tcbs-style-newsletter.eml")));
        fixtureLinks = new ContentAnalyzer(new TrackingLinkUnwrapper()).analyzeHtml(email.htmlBody()).links();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Báo cáo thị trường trái phiếu - Quý III/2026 | MARKET_REPORT   | -   | READY
            IMP - Cập nhật KQKD Q3/2026                  | STOCK_RESEARCH  | IMP | READY
            IMP - Q3/2026 Earnings Update                | STOCK_RESEARCH  | IMP | READY
            Xem toàn bộ báo cáo                          | NAVIGATION      | -   | SKIPPED
            VIC                                          | STOCK_RESEARCH  | VIC | READY
            SCS                                          | STOCK_RESEARCH  | SCS | READY
            Giảm lãi suất Margin                         | PROMOTION       | -   | SKIPPED
            Đăng ký nhận email                           | NAVIGATION      | -   | SKIPPED
            Facebook                                     | NAVIGATION      | -   | SKIPPED
            Hủy nhận các email                           | NAVIGATION      | -   | SKIPPED
            """)
    void classifiesFixtureLinks(String text, LinkCategory category, String ticker, LinkStatus status) {
        ExtractedLink link = fixtureLinks.stream().filter(l -> text.equals(l.displayText())).findFirst().orElseThrow();

        Classification result = classifier.classify(link.targetUrl(), link.host(), link.displayText(), link.section());

        assertThat(result.category()).isEqualTo(category);
        assertThat(result.tickerHint()).isEqualTo(ticker);
        assertThat(result.initialStatus()).isEqualTo(status);
        assertThat(result.rule()).isNotBlank();
    }

    @Test
    void englishOneClickReportKeepsTickerFromFileName() {
        Classification result = classifier.classify("https://static.tcbs.com.vn/oneclick/SCS_EN.pdf?utm=1", "static.tcbs.com.vn", null, null);

        assertThat(result.category()).isEqualTo(LinkCategory.STOCK_RESEARCH);
        assertThat(result.tickerHint()).isEqualTo("SCS");
    }

    @Test
    void genericPdfWithoutHintsIsResearchReport() {
        Classification result = classifier.classify("https://www.ssi.com.vn/files/20261028_daily.pdf", "www.ssi.com.vn", "Daily", null);

        assertThat(result.category()).isEqualTo(LinkCategory.RESEARCH_REPORT);
        assertThat(result.rule()).isEqualTo("research.pdf");
    }

    @Test
    void currencyCodesAreNotTickers() {
        Classification result = classifier.classify("https://www.ssi.com.vn/files/USD_outlook.pdf", "www.ssi.com.vn", "USD outlook", null);

        assertThat(result.tickerHint()).isNull();
        assertThat(result.category()).isEqualTo(LinkCategory.MARKET_REPORT);
    }

    @Test
    void nonPdfReportLinkIsDiscoveredNotReady() {
        Classification result = classifier.classify("https://www.vndirect.com.vn/bao-cao/vnm-q3", "www.vndirect.com.vn", "Báo cáo VNM Q3", null);

        assertThat(result.category()).isEqualTo(LinkCategory.RESEARCH_REPORT);
        assertThat(result.initialStatus()).isEqualTo(LinkStatus.DISCOVERED);
    }

    @Test
    void unknownLinkIsOther() {
        Classification result = classifier.classify("https://www.example.org/about", "www.example.org", "About us", null);

        assertThat(result.category()).isEqualTo(LinkCategory.OTHER);
        assertThat(result.initialStatus()).isEqualTo(LinkStatus.DISCOVERED);
    }
}

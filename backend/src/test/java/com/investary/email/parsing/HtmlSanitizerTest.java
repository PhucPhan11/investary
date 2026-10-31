package com.investary.email.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer(new TrackingLinkUnwrapper());

    @Test
    void removesActiveContent() {
        String html = sanitizer.sanitize("""
                <p onclick="steal()">Hi<script>alert(1)</script></p>
                <iframe src="https://evil.example"></iframe>
                <form action="https://evil.example"><input name="pw"></form>
                <a href="javascript:alert(1)">js</a>
                <object data="x.swf"></object><svg onload="x()"></svg>
                <div style="background:url(https://tracker.example/p.gif)">styled</div>""");
        String body = Jsoup.parse(html).body().html();

        assertThat(body).doesNotContain("script", "onclick", "iframe", "<form", "<input", "javascript:", "object", "onload", "tracker.example");
        assertThat(body).contains("Hi", "styled");
    }

    @Test
    void replacesImagesWithAltTextSoNothingRemoteLoads() {
        Document doc = Jsoup.parse(sanitizer.sanitize("<img src=\"https://t.example/pixel.gif\"><img src=\"https://cdn.example/a.png\" alt=\"Logo\">"));

        assertThat(doc.select("img")).isEmpty();
        assertThat(doc.body().text()).isEqualTo("[Logo]");
    }

    @Test
    void unwrapsTrackingLinksAndOpensThemSafely() {
        Document doc = Jsoup.parse(sanitizer.sanitize(
                "<a href=\"https://x.r.ap-southeast-1.awstrack.me/L0/https:%2F%2Fwww.tcbs.com.vn%2Fa.pdf/1/m/s=1\">Report</a>"));

        assertThat(doc.select("a").attr("href")).isEqualTo("https://www.tcbs.com.vn/a.pdf");
        assertThat(doc.select("a").attr("rel")).isEqualTo("noopener noreferrer nofollow");
        assertThat(doc.select("a").attr("target")).isEqualTo("_blank");
    }

    @Test
    void dropsInvisiblePreheaderFiller() {
        String html = sanitizer.sanitize("<span style=\"display:none\">Preview&#847;&zwnj;&#847;&zwnj;&shy;</span><p>Body</p>");

        assertThat(Jsoup.parse(html).body().text()).isEqualTo("Preview Body");
    }

    @Test
    void wrapsOutputInDocumentWithRestrictiveCsp() {
        String html = sanitizer.sanitize("<p>x</p>");

        assertThat(html).contains("Content-Security-Policy", "default-src 'none'", "no-referrer");
    }
}

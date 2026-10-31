package com.investary.research.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.investary.research.link.BrokerageRegistry;
import com.investary.research.link.ResearchProperties;

class UrlPolicyTest {

    private final UrlPolicy policy = new UrlPolicy(
            new DownloadProperties(false, false, false, List.of("reports.example.org"), null, null, null, null, 0, null, null, 0, null, null),
            new BrokerageRegistry(new ResearchProperties(List.of(new ResearchProperties.Brokerage("TCBS", List.of("tcbs.com.vn"))))));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.tcbs.com.vn/wp-content/uploads/report.pdf",
            "https://static.tcbs.com.vn/oneclick/VIC.pdf?utm_source=email",
            "https://tcbs.com.vn:443/a.pdf",
            "https://reports.example.org/x.pdf"})
    void allowsHttpsOnAllowlistedDomains(String url) {
        assertThatCode(() -> policy.check(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://www.tcbs.com.vn/report.pdf",
            "ftp://www.tcbs.com.vn/report.pdf",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:application/pdf;base64,JVBERi0=",
            "https://evil.example/report.pdf",
            "https://tcbs.com.vn.evil.example/report.pdf",
            "https://eviltcbs.com.vn/report.pdf",
            "https://user:pw@www.tcbs.com.vn/report.pdf",
            "https://www.tcbs.com.vn:8443/report.pdf",
            "https://127.0.0.1/report.pdf",
            "https://169.254.169.254/latest/meta-data",
            "not a url"})
    void blocksEverythingElse(String url) {
        assertThatThrownBy(() -> policy.check(url))
                .isInstanceOfSatisfying(DownloadException.class, e -> assertThat(e.getKind()).isEqualTo(DownloadException.Kind.BLOCKED));
    }
}

package com.investary.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.investary.automation.application.AutomationExceptions.InvalidSourceRuleException;
import com.investary.automation.application.SourceRuleService.SourceRuleCommand;

class SourceRuleServiceTest {

    static SourceRuleCommand command(List<String> addresses, List<String> domains, List<String> keywords, String query) {
        return new SourceRuleCommand("TCBS", addresses, domains, keywords, query, null, null, null);
    }

    @Test
    void normalisesSenders() {
        var normalized = SourceRuleService.normalize(command(Arrays.asList(" Research@TCBS.com.vn ", "", null, "research@tcbs.com.vn"),
                List.of("@TCBS.com.vn", "ssi.com.vn"), List.of(" bản tin "), "  "));
        assertThat(normalized.addresses()).containsExactly("research@tcbs.com.vn");
        assertThat(normalized.domains()).containsExactly("tcbs.com.vn", "ssi.com.vn");
        assertThat(normalized.keywords()).containsExactly("bản tin");
        assertThat(normalized.gmailQuery()).isNull();
    }

    @Test
    void reportsFieldErrors() {
        assertThatThrownBy(() -> SourceRuleService.normalize(command(List.of(), List.of(), List.of(), null)))
                .isInstanceOfSatisfying(InvalidSourceRuleException.class,
                        e -> assertThat(e.getProperties().get("errors").toString()).contains("senderAddresses"));
        assertThatThrownBy(() -> SourceRuleService.normalize(command(List.of("TCBS <a@tcbs.com.vn>"), List.of("not a domain"),
                List.of(), "in:anywhere")))
                .isInstanceOfSatisfying(InvalidSourceRuleException.class, e -> assertThat(e.getProperties().get("errors").toString())
                        .contains("senderAddresses", "senderDomains", "gmailQuery"));
    }
}

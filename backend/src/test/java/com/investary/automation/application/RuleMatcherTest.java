package com.investary.automation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.investary.automation.domain.EmailSourceRule;

class RuleMatcherTest {

    static EmailSourceRule rule(String name, List<String> addresses, List<String> domains, List<String> keywords, String query,
                                boolean enabled) {
        EmailSourceRule rule = new EmailSourceRule(Instant.EPOCH);
        rule.update(name, addresses, domains, keywords, query, enabled, true, false, Instant.EPOCH);
        return rule;
    }

    @Test
    void matchesAddressesAndDomainsIncludingSubdomainsButNotLookalikes() {
        EmailSourceRule tcbs = rule("TCBS", List.of(), List.of("tcbs.com.vn"), List.of(), null, true);
        EmailSourceRule ssi = rule("SSI", List.of("research@ssi.com.vn"), List.of(), List.of(), null, true);
        List<EmailSourceRule> rules = List.of(tcbs, ssi);

        assertThat(RuleMatcher.match(rules, "research@tcbs.com.vn", "x")).contains(tcbs);
        assertThat(RuleMatcher.match(rules, "news@mail.tcbs.com.vn", "x")).contains(tcbs);
        assertThat(RuleMatcher.match(rules, "news@eviltcbs.com.vn", "x")).isEmpty();
        assertThat(RuleMatcher.match(rules, "news@tcbs.com.vn.evil.example", "x")).isEmpty();
        assertThat(RuleMatcher.match(rules, "research@ssi.com.vn", "x")).contains(ssi);
        assertThat(RuleMatcher.match(rules, "other@ssi.com.vn", "x")).isEmpty();
    }

    @Test
    void appliesSubjectKeywordsWithoutDiacriticsAndSkipsDisabledRules() {
        EmailSourceRule rule = rule("VCBS", List.of(), List.of("vcbs.com.vn"), List.of("Báo cáo", "Đầu tư"), null, true);
        assertThat(RuleMatcher.match(List.of(rule), "a@vcbs.com.vn", "BAO CAO thi truong")).contains(rule);
        assertThat(RuleMatcher.match(List.of(rule), "a@vcbs.com.vn", "Cơ hội dau tu tuần này")).contains(rule);
        assertThat(RuleMatcher.match(List.of(rule), "a@vcbs.com.vn", "Ưu đãi tháng 11")).isEmpty();

        EmailSourceRule disabled = rule("Off", List.of(), List.of("vcbs.com.vn"), List.of(), null, false);
        assertThat(RuleMatcher.match(List.of(disabled), "a@vcbs.com.vn", "x")).isEmpty();
    }

    @Test
    void buildsTheGmailPrefilter() {
        EmailSourceRule rule = rule("TCBS", List.of("research@tcbs.com.vn"), List.of("tcbs.com.vn"), List.of(),
                "subject:(\"bản tin\")", true);
        assertThat(RuleMatcher.gmailQuery(rule, Instant.ofEpochSecond(1790000000)))
                .isEqualTo("from:(research@tcbs.com.vn OR tcbs.com.vn) (subject:(\"bản tin\")) after:1790000000");
    }
}

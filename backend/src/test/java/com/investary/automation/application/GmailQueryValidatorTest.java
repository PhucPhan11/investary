package com.investary.automation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GmailQueryValidatorTest {

    @Test
    void acceptsNarrowingQueries() {
        assertThat(GmailQueryValidator.problem(null)).isEmpty();
        assertThat(GmailQueryValidator.problem("  ")).isEmpty();
        assertThat(GmailQueryValidator.problem("subject:(\"bản tin\" OR newsletter) has:attachment")).isEmpty();
        assertThat(GmailQueryValidator.problem("-category:promotions label:research")).isEmpty();
    }

    @Test
    void rejectsMalformedOrWideningQueries() {
        assertThat(GmailQueryValidator.problem("a".repeat(501))).isPresent();
        assertThat(GmailQueryValidator.problem("subject:x\nfrom:y")).isPresent();
        assertThat(GmailQueryValidator.problem("(subject:x")).isPresent();
        assertThat(GmailQueryValidator.problem("subject:x)")).isPresent();
        assertThat(GmailQueryValidator.problem("subject:\"x")).isPresent();
        assertThat(GmailQueryValidator.problem("in:anywhere")).isPresent();
        assertThat(GmailQueryValidator.problem("subject:x OR in:spam")).isPresent();
        assertThat(GmailQueryValidator.problem("(IN:TRASH)")).isPresent();
        assertThat(GmailQueryValidator.problem("is:chats")).isPresent();
    }
}

package com.investary.automation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SenderVerifierTest {

    @Test
    void usesTheAddrSpecNeverTheDisplayName() {
        assertThat(SenderVerifier.senderAddress(List.of("TCBS Research <Research@TCBS.com.vn>"))).contains("research@tcbs.com.vn");
        assertThat(SenderVerifier.senderAddress(List.of("\"research@tcbs.com.vn\" <attacker@evil.example>")))
                .contains("attacker@evil.example");
        assertThat(SenderVerifier.senderAddress(List.of("=?UTF-8?B?VENCUw==?= <news@ssi.com.vn>"))).contains("news@ssi.com.vn");
    }

    @Test
    void rejectsMissingMultipleOrInvalidSenders() {
        assertThat(SenderVerifier.senderAddress(List.of())).isEmpty();
        assertThat(SenderVerifier.senderAddress(List.of("a@tcbs.com.vn", "b@tcbs.com.vn"))).isEmpty();
        assertThat(SenderVerifier.senderAddress(List.of("a@tcbs.com.vn, b@evil.example"))).isEmpty();
        assertThat(SenderVerifier.senderAddress(List.of("TCBS"))).isEmpty();
        assertThat(SenderVerifier.senderAddress(List.of("Group: a@tcbs.com.vn;"))).isEmpty();
    }

    @Test
    void readsOnlyGmailsOwnDmarcVerdict() {
        assertThat(SenderVerifier.failedDmarc(List.of(
                "mx.google.com; dkim=fail header.i=@tcbs.com.vn; spf=softfail; dmarc=fail (p=REJECT) header.from=tcbs.com.vn"))).isTrue();
        assertThat(SenderVerifier.failedDmarc(List.of(
                "mx.google.com; dkim=pass; spf=pass; dmarc=pass (p=NONE) header.from=tcbs.com.vn",
                "mx.google.com; dmarc=fail"))).as("only the topmost Gmail header counts").isFalse();
        assertThat(SenderVerifier.failedDmarc(List.of("evil.example; dmarc=fail"))).as("not Gmail's").isFalse();
        assertThat(SenderVerifier.failedDmarc(List.of())).isFalse();
    }
}

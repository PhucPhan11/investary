package com.investary.automation.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.investary.automation.GmailProperties;
import com.investary.automation.security.OAuthStateStore.Authorization;

class OAuthStateStoreTest {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    final OAuthStateStore store = new OAuthStateStore(new GmailProperties("id", "secret", "k", null, null, null, null, null, null,
            null, null, 0, null, null, Duration.ofMinutes(10), null), clock);

    @Test
    void acceptsAStateOnceFromTheSameBrowser() {
        Authorization auth = store.start();

        assertThat(auth.state()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(auth.codeVerifier()).hasSizeBetween(43, 128);
        assertThat(auth.codeChallenge()).isEqualTo(OAuthStateStore.challenge(auth.codeVerifier())).doesNotContain("=");
        assertThat(store.consume(auth.state(), auth.binding())).contains(auth.codeVerifier());
        assertThat(store.consume(auth.state(), auth.binding())).as("replay").isEmpty();
    }

    @Test
    void rejectsUnknownForeignAndExpiredStates() {
        Authorization auth = store.start();
        assertThat(store.consume("forged", auth.binding())).isEmpty();
        assertThat(store.consume(null, auth.binding())).isEmpty();

        Authorization other = store.start();
        assertThat(store.consume(other.state(), "another-browser")).isEmpty();
        assertThat(store.consume(other.state(), other.binding())).as("a failed attempt burns the state").isEmpty();

        Authorization late = store.start();
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(store.consume(late.state(), late.binding())).isEmpty();
    }

    @Test
    void knownPkceVector() {
        // RFC 7636 appendix B
        assertThat(OAuthStateStore.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void staysBounded() {
        for (int i = 0; i < 50; i++) {
            store.start();
        }
        assertThat(store.size()).isLessThanOrEqualTo(OAuthStateStore.MAX_PENDING);
    }
}

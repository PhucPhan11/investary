package com.investary.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AiConcurrencyLimitsTest {

    @Test
    void permitsCanBeRaisedAndLoweredWhileCallsRun() throws InterruptedException {
        AiConcurrencyLimits limits = new AiConcurrencyLimits(2, 1);
        assertThat(limits.acquireChat(Duration.ZERO)).isTrue();
        assertThat(limits.acquireChat(Duration.ZERO)).isTrue();
        assertThat(limits.acquireChat(Duration.ZERO)).isFalse();

        limits.resize(3, 1);
        assertThat(limits.chatLimit()).isEqualTo(3);
        assertThat(limits.acquireChat(Duration.ZERO)).isTrue();

        // Lowered while three calls run: a new call waits until enough of them have finished.
        limits.resize(1, 1);
        limits.releaseChat();
        limits.releaseChat();
        assertThat(limits.acquireChat(Duration.ZERO)).isFalse();
        limits.releaseChat();
        assertThat(limits.acquireChat(Duration.ZERO)).isTrue();
        assertThat(limits.availableChatPermits()).isZero();
    }
}

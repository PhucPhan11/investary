package com.investary.knowledge;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared permits for AI provider calls, so background automation can never run more model calls at once
 * than configured, whatever the number of worker threads.
 */
@Component
public class AiConcurrencyLimits {

    private final Semaphore chat;
    private final Semaphore embedding;

    @Autowired
    public AiConcurrencyLimits(AiLimitsProperties properties) {
        this(properties.maxConcurrentChat(), properties.maxConcurrentEmbedding());
    }

    public AiConcurrencyLimits(int chat, int embedding) {
        this.chat = new Semaphore(chat, true);
        this.embedding = new Semaphore(embedding, true);
    }

    /** Effectively unlimited permits, for unit tests that construct services directly. */
    public static AiConcurrencyLimits unlimited() {
        return new AiConcurrencyLimits(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
    }

    /**
     * @return false if no chat permit became free within {@code wait}
     */
    public boolean acquireChat(Duration wait) throws InterruptedException {
        return chat.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void releaseChat() {
        chat.release();
    }

    public void acquireEmbedding() throws InterruptedException {
        embedding.acquire();
    }

    public void releaseEmbedding() {
        embedding.release();
    }

    public int availableChatPermits() {
        return chat.availablePermits();
    }
}

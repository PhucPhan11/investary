package com.investary.knowledge;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared permits for AI provider calls, so background automation can never run more model calls at once
 * than configured, whatever the number of worker threads. The number of permits can be changed at runtime
 * ({@link #resize}); calls already holding a permit finish normally.
 */
@Component
public class AiConcurrencyLimits {

    private final ResizableSemaphore chat;
    private final ResizableSemaphore embedding;

    @Autowired
    public AiConcurrencyLimits(AiLimitsProperties properties) {
        this(properties.maxConcurrentChat(), properties.maxConcurrentEmbedding());
    }

    public AiConcurrencyLimits(int chat, int embedding) {
        this.chat = new ResizableSemaphore(chat);
        this.embedding = new ResizableSemaphore(embedding);
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

    public int chatLimit() {
        return chat.size();
    }

    public int embeddingLimit() {
        return embedding.size();
    }

    /** Changes the number of permits; lowering it takes effect as running calls finish. */
    public void resize(int chatPermits, int embeddingPermits) {
        chat.resize(Math.max(1, chatPermits));
        embedding.resize(Math.max(1, embeddingPermits));
    }

    /** A fair semaphore whose total number of permits can change. */
    static final class ResizableSemaphore extends Semaphore {

        private int size;

        ResizableSemaphore(int size) {
            super(size, true);
            this.size = size;
        }

        synchronized int size() {
            return size;
        }

        synchronized void resize(int newSize) {
            int delta = newSize - size;
            if (delta > 0) {
                release(delta);
            } else if (delta < 0) {
                reducePermits(-delta);
            }
            size = newSize;
        }
    }
}

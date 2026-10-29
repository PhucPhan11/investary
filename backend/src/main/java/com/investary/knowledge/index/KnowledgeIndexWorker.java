package com.investary.knowledge.index;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.embedding.EmbeddingService;

/**
 * Background indexing, built like the download worker: it polls the table, claims only as many sources
 * as it has free threads and recovers expired leases. It also tracks new documents and emails
 * (automatic indexing and backfill). Without a configured embedding model nothing is claimed; tracked
 * sources wait in PENDING and the status endpoint explains why.
 */
@Component
@ConditionalOnProperty(prefix = "investary.knowledge.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexWorker.class);

    private final KnowledgeIndexQueue queue;
    private final KnowledgeIndexProcessor processor;
    private final EmbeddingService embeddings;
    private final KnowledgeProperties properties;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private Instant nextReconcile = Instant.MIN;

    public KnowledgeIndexWorker(KnowledgeIndexQueue queue, KnowledgeIndexProcessor processor, EmbeddingService embeddings,
                                KnowledgeProperties properties, Clock clock) {
        this.queue = queue;
        this.processor = processor;
        this.embeddings = embeddings;
        this.properties = properties;
        this.clock = clock;
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(properties.worker().concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "knowledge-worker-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${investary.knowledge.worker.poll-interval:3s}", initialDelayString = "${investary.knowledge.worker.poll-interval:3s}")
    public void poll() {
        try {
            reconcileIfDue();
            if (!embeddings.isAvailable()) {
                return;
            }
            int recovered = queue.recoverExpired(properties.worker().lease());
            if (recovered > 0) {
                log.warn("Re-queued {} indexing job(s) whose lease expired", recovered);
            }
            int free = properties.worker().concurrency() - inFlight.get();
            for (KnowledgeIndexQueue.ClaimedSource job : queue.claim(free)) {
                inFlight.incrementAndGet();
                executor.execute(() -> {
                    try {
                        processor.process(job);
                    } catch (RuntimeException e) {
                        log.error("Unexpected indexing error knowledgeSourceId={}", job.id(), e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.error("Knowledge index polling failed", e);
        }
    }

    private void reconcileIfDue() {
        Instant now = clock.instant();
        if (!properties.autoIndex() || now.isBefore(nextReconcile)) {
            return;
        }
        nextReconcile = now.plus(properties.worker().reconcileInterval());
        int added = queue.trackMissing(properties.worker().maxAttempts());
        if (added > 0) {
            log.info("Queued {} new source(s) for indexing", added);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            // Unfinished jobs stay PROCESSING and are re-queued after their lease expires.
            executor.shutdownNow();
        }
    }
}

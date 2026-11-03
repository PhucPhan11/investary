package com.investary.intelligence.application;

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

import com.investary.intelligence.persistence.BriefJobQueue;
import com.investary.intelligence.persistence.BriefJobQueue.ClaimedJob;

/**
 * Enqueues due scheduled briefs and runs brief jobs on a bounded thread pool, like the other workers: it
 * only claims as many jobs as it has free threads, and abandoned jobs are re-queued after their lease.
 */
@Component
@ConditionalOnProperty(prefix = "investary.brief.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BriefJobWorker implements DisposableBean {

    private volatile Instant lastPollAt;

    private static final Logger log = LoggerFactory.getLogger(BriefJobWorker.class);

    private final BriefScheduler scheduler;
    private final BriefJobQueue queue;
    private final BriefJobProcessor processor;
    private final BriefProperties.Worker settings;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public BriefJobWorker(BriefScheduler scheduler, BriefJobQueue queue, BriefJobProcessor processor, BriefProperties properties) {
        this.scheduler = scheduler;
        this.queue = queue;
        this.processor = processor;
        this.settings = properties.worker();
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(settings.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "brief-worker-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${investary.brief.worker.poll-interval:30s}", initialDelayString = "${investary.brief.worker.poll-interval:30s}")
    public void poll() {
        lastPollAt = Instant.now();
        try {
            scheduler.enqueueDue();
            int recovered = queue.recoverExpired(settings.lease());
            if (recovered > 0) {
                log.warn("Re-queued {} brief job(s) whose lease expired", recovered);
            }
            for (ClaimedJob job : queue.claim(settings.concurrency() - inFlight.get())) {
                inFlight.incrementAndGet();
                executor.execute(() -> {
                    try {
                        processor.process(job);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.error("Brief job polling failed", e);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            // Unfinished jobs stay RUNNING and are re-queued after their lease expires.
            executor.shutdownNow();
        }
    }

    /** When the worker last polled (health check heartbeat), or null before the first poll. */
    public Instant lastPollAt() {
        return lastPollAt;
    }
}

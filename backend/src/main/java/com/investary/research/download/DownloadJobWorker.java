package com.investary.research.download;

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

/**
 * Polls the job table and runs downloads on a bounded thread pool. It only claims as many jobs as
 * it has free threads, so jobs never wait in memory where a restart could lose them.
 */
@Component
@ConditionalOnProperty(prefix = "investary.download.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DownloadJobWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DownloadJobWorker.class);

    private final DownloadJobQueue queue;
    private final DownloadJobProcessor processor;
    private final DownloadProperties.Worker settings;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public DownloadJobWorker(DownloadJobQueue queue, DownloadJobProcessor processor, DownloadProperties properties) {
        this.queue = queue;
        this.processor = processor;
        this.settings = properties.worker();
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(settings.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "download-worker-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${investary.download.worker.poll-interval:2s}", initialDelayString = "${investary.download.worker.poll-interval:2s}")
    public void poll() {
        try {
            int recovered = queue.recoverExpired(settings.lease());
            if (recovered > 0) {
                log.warn("Re-queued {} download job(s) whose lease expired", recovered);
            }
            int free = settings.concurrency() - inFlight.get();
            for (DownloadJobQueue.ClaimedJob job : queue.claim(free)) {
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
            log.error("Download job polling failed", e);
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
}

package com.investary.automation.application.workflow;

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

import com.investary.automation.WorkflowProperties;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;

/**
 * Polls the automation job table and runs jobs on a bounded thread pool, like the download and indexing
 * workers: it only claims as many jobs as it has free threads, so no job waits in memory where a restart
 * could lose it. Abandoned jobs are re-queued after their lease, and new sources whose event was missed
 * are picked up by a periodic reconcile.
 */
@Component
@ConditionalOnProperty(prefix = "investary.workflow.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutomationJobWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AutomationJobWorker.class);

    private final AutomationJobQueue queue;
    private final WorkflowJobProcessor processor;
    private final WorkflowService workflows;
    private final WorkflowProperties.Worker settings;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Instant nextReconcile = Instant.EPOCH;

    public AutomationJobWorker(AutomationJobQueue queue, WorkflowJobProcessor processor, WorkflowService workflows,
                               WorkflowProperties properties, Clock clock) {
        this.queue = queue;
        this.processor = processor;
        this.workflows = workflows;
        this.settings = properties.worker();
        this.clock = clock;
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(settings.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "workflow-worker-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${investary.workflow.worker.poll-interval:2s}", initialDelayString = "${investary.workflow.worker.poll-interval:2s}")
    public void poll() {
        try {
            reconcileIfDue();
            int recovered = queue.recoverExpired(settings.lease());
            if (recovered > 0) {
                log.warn("Re-queued {} workflow job(s) whose lease expired", recovered);
            }
            int free = settings.concurrency() - inFlight.get();
            for (ClaimedJob job : queue.claim(free)) {
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
            log.error("Workflow job polling failed", e);
        }
    }

    private void reconcileIfDue() {
        Instant now = clock.instant();
        if (now.isBefore(nextReconcile)) {
            return;
        }
        nextReconcile = now.plus(settings.reconcileInterval());
        int started = workflows.reconcile();
        if (started > 0) {
            log.info("Started {} workflow(s) for sources whose import event was missed", started);
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

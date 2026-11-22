package com.investary.notebook.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.investary.notebook.persistence.NoteQueue;
import com.investary.notebook.persistence.NoteQueue.ClaimedNote;

/**
 * Runs pending notebook notes one at a time on a single daemon thread. Abandoned notes are re-queued after their
 * lease. Off in tests, which drive notes with {@code NoteQueue.claim} + {@code NoteJobProcessor.process}.
 */
@Component
@ConditionalOnProperty(prefix = "investary.notebook.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NoteWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NoteWorker.class);

    private final NoteQueue queue;
    private final NoteJobProcessor processor;
    private final NotebookProperties properties;
    private final ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean();

    public NoteWorker(NoteQueue queue, NoteJobProcessor processor, NotebookProperties properties) {
        this.queue = queue;
        this.processor = processor;
        this.properties = properties;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notebook-note-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${investary.notebook.worker.poll-interval:5s}",
            initialDelayString = "${investary.notebook.worker.poll-interval:5s}")
    public void poll() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            int recovered = queue.recoverExpired(properties.worker().lease());
            if (recovered > 0) {
                log.warn("Re-queued {} notebook note(s) whose lease expired", recovered);
            }
            for (ClaimedNote note : queue.claim(1)) {
                executor.execute(() -> {
                    try {
                        processor.process(note);
                    } finally {
                        busy.set(false);
                    }
                });
                return;
            }
            busy.set(false);
        } catch (RuntimeException e) {
            busy.set(false);
            log.error("Notebook note polling failed", e);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            // Unfinished notes stay RUNNING and are re-queued after their lease expires.
            executor.shutdownNow();
        }
    }
}

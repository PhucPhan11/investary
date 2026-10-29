package com.investary.knowledge;

import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;

import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;

/**
 * Runs indexing synchronously in tests (the background worker is disabled there).
 */
public final class KnowledgeTestSupport {

    private KnowledgeTestSupport() {
    }

    /**
     * Tracks all sources and processes queued jobs until none is left (retries included).
     */
    public static void indexAll(KnowledgeIndexQueue queue, KnowledgeIndexProcessor processor) {
        queue.trackMissing(3);
        processQueued(queue, processor);
    }

    public static void processQueued(KnowledgeIndexQueue queue, KnowledgeIndexProcessor processor) {
        for (int round = 0; round < 20; round++) {
            List<KnowledgeIndexQueue.ClaimedSource> claimed = queue.claim(50);
            if (claimed.isEmpty()) {
                // Retries are scheduled a few milliseconds ahead; give them a moment before concluding.
                Awaitility.await().pollDelay(Duration.ofMillis(20)).until(() -> true);
                claimed = queue.claim(50);
                if (claimed.isEmpty()) {
                    return;
                }
            }
            claimed.forEach(processor::process);
        }
    }
}

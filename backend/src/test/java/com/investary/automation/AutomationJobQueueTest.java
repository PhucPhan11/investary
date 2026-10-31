package com.investary.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;
import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowTrigger;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore;
import com.investary.document.TestPdfs;
import com.investary.document.application.DocumentService;
import com.investary.document.application.UploadDocumentCommand;

/**
 * The automation job queue against PostgreSQL: concurrent claims, idempotent enqueueing, waits that use
 * no attempt, and lease recovery that makes a stale worker's writes no-ops.
 */
@SpringBootTest(properties = "investary.workflow.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class AutomationJobQueueTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path storage = Files.createTempDirectory("investary-queue-test-");
        registry.add("investary.storage.root", () -> storage.resolve("documents").toString());
        registry.add("investary.email.storage.root", () -> storage.resolve("emails").toString());
    }

    @Autowired
    AutomationJobQueue queue;

    @Autowired
    WorkflowStore store;

    @Autowired
    DocumentService documents;

    @Autowired
    JdbcTemplate jdbc;

    private UUID workflowId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from document");
        UUID document = documents.upload(new UploadDocumentCommand(new ByteArrayResource(TestPdfs.pdf("queue-" + UUID.randomUUID())),
                "q.pdf", null, null, null, null)).getId();
        workflowId = store.create(WorkflowSourceType.DOCUMENT, document, WorkflowTrigger.MANUAL, "w1", null, Instant.now()).orElseThrow();
    }

    @Test
    void concurrentClaimsNeverReturnTheSameJob() throws Exception {
        for (int i = 0; i < 40; i++) {
            queue.enqueue(workflowId, JobType.INDEX, null, "concurrent-" + i, 3, "w1");
        }
        Callable<List<UUID>> claimer = () -> {
            List<UUID> ids = new ArrayList<>();
            List<ClaimedJob> batch;
            while (!(batch = queue.claim(3)).isEmpty()) {
                batch.forEach(job -> ids.add(job.id()));
            }
            return ids;
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<UUID>>> results = pool.invokeAll(List.of(claimer, claimer, claimer, claimer));
            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> result : results) {
                all.addAll(result.get());
            }
            Set<UUID> unique = new HashSet<>(all);
            assertThat(all).hasSize(40);
            assertThat(unique).hasSize(40);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void enqueueIsIdempotentAndReopensOnlyFinishedJobs() {
        UUID first = queue.enqueue(workflowId, JobType.MATCH, null, "key-match", 3, "w1");
        UUID again = queue.enqueue(workflowId, JobType.MATCH, null, "key-match", 3, "w1");
        assertThat(again).isEqualTo(first);
        assertThat(queue.forWorkflow(workflowId)).hasSize(1);

        ClaimedJob claimed = queue.claim(1).getFirst();
        queue.enqueue(workflowId, JobType.MATCH, null, "key-match", 3, "w1");
        assertThat(queue.find(first).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);

        assertThat(queue.complete(claimed, "DONE", 10, 5)).isTrue();
        queue.enqueue(workflowId, JobType.MATCH, null, "key-match", 3, "w1");
        var reopened = queue.find(first).orElseThrow();
        assertThat(reopened.status()).isEqualTo(JobStatus.PENDING);
        assertThat(reopened.attempts()).isZero();
        assertThat(reopened.outcome()).isNull();
        assertThat(reopened.inputTokens()).isEqualTo(10);
    }

    @Test
    void waitingUsesNoAttemptAndResumes() {
        queue.enqueue(workflowId, JobType.INDEX, null, "key-wait", 3, "w1");
        ClaimedJob first = queue.claim(1).getFirst();
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.resumed()).isFalse();

        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        assertThat(queue.waitUntil(first, Instant.now().minusSeconds(1), deadline)).isTrue();
        assertThat(queue.find(first.id()).orElseThrow().status()).isEqualTo(JobStatus.WAITING);

        ClaimedJob resumed = queue.claim(1).getFirst();
        assertThat(resumed.attempts()).isEqualTo(1);
        assertThat(resumed.resumed()).isTrue();
        assertThat(resumed.waitDeadline()).isNotNull();
    }

    @Test
    void expiredLeaseIsRecoveredAndTheStaleWorkerCannotWrite() {
        queue.enqueue(workflowId, JobType.INDEX, null, "key-lease", 1, "w1");
        ClaimedJob stale = queue.claim(1).getFirst();
        jdbc.update("update automation_jobs set claimed_at = now() - interval '1 hour' where id = ?", stale.id());

        assertThat(queue.recoverExpired(Duration.ofMinutes(15))).isEqualTo(1);
        // max_attempts = 1 and one attempt used: the job fails instead of running again.
        var recovered = queue.find(stale.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(JobStatus.FAILED);
        assertThat(recovered.errorCode()).isEqualTo("INTERRUPTED");

        assertThat(queue.isClaimed(stale)).isFalse();
        assertThat(queue.complete(stale, "LATE", null, null)).isFalse();
        assertThat(queue.find(stale.id()).orElseThrow().status()).isEqualTo(JobStatus.FAILED);

        assertThat(queue.resetFailed(stale.id())).isTrue();
        assertThat(queue.resetFailed(stale.id())).isFalse();
        assertThat(queue.find(stale.id()).orElseThrow().attempts()).isZero();
    }
}

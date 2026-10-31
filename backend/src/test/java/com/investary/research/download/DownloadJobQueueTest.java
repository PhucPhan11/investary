package com.investary.research.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;
import com.investary.research.download.DownloadJobQueue.ClaimedJob;

/**
 * Job-claim semantics against real PostgreSQL ({@code FOR UPDATE SKIP LOCKED}, leases, claim tokens).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DownloadJobQueueTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    DownloadJobQueue queue;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from source_messages");
    }

    @Test
    void concurrentClaimersNeverGetTheSameJob() throws Exception {
        createJobs(20);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<ClaimedJob>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit((Callable<List<ClaimedJob>>) () -> queue.claim(5)));
            }
            Set<UUID> claimed = new HashSet<>();
            int total = 0;
            for (Future<List<ClaimedJob>> future : futures) {
                for (ClaimedJob job : future.get()) {
                    claimed.add(job.jobId());
                    total++;
                }
            }
            assertThat(total).isEqualTo(20);
            assertThat(claimed).hasSize(20);
        } finally {
            pool.shutdownNow();
        }
        assertThat(queue.claim(5)).isEmpty();
    }

    @Test
    void expiredLeaseIsRequeuedAndStaleWorkerCannotComplete() {
        createJobs(1);
        ClaimedJob stale = queue.claim(1).getFirst();
        jdbc.update("update download_jobs set claimed_at = now() - interval '10 minutes'");

        assertThat(queue.recoverExpired(Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from download_jobs", String.class)).isEqualTo("PENDING");

        ClaimedJob fresh = queue.claim(1).getFirst();
        assertThat(fresh.attempts()).isEqualTo(2);
        assertThat(queue.complete(stale)).as("old claim token is rejected").isFalse();
        assertThat(queue.complete(fresh)).isTrue();
        assertThat(jdbc.queryForObject("select status from download_jobs", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredLeaseOnLastAttemptFailsJobAndLink() {
        createJobs(1);
        jdbc.update("update download_jobs set max_attempts = 1");
        queue.claim(1);
        jdbc.update("update download_jobs set claimed_at = now() - interval '10 minutes'");

        queue.recoverExpired(Duration.ofMinutes(5));

        assertThat(jdbc.queryForObject("select status from download_jobs", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from source_links", String.class)).isEqualTo("FAILED");
    }

    @Test
    void retryLaterHidesJobUntilNextAttempt() {
        createJobs(1);
        ClaimedJob job = queue.claim(1).getFirst();

        assertThat(queue.retryLater(job, "HTTP 503", java.time.Instant.now().plusSeconds(60))).isTrue();

        assertThat(queue.claim(1)).isEmpty();
        assertThat(jdbc.queryForObject("select last_error from download_jobs", String.class)).isEqualTo("HTTP 503");
    }

    private void createJobs(int count) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                insert into source_messages (id, source_type, storage_key, original_filename, file_size, content_sha256, parsing_status)
                values (?, 'EML_UPLOAD', ?, 'test.eml', 1, ?, 'PARSED')""",
                messageId, "2026/10/" + UUID.randomUUID() + ".eml", com.investary.common.util.Hashing.sha256(messageId.toString().getBytes()));
        for (int i = 0; i < count; i++) {
            UUID linkId = UUID.randomUUID();
            jdbc.update("""
                    insert into source_links (id, source_message_id, position, original_url, target_url, host, category, classification_rule, status)
                    values (?, ?, ?, ?, ?, 'www.tcbs.com.vn', 'RESEARCH_REPORT', 'test', 'DOWNLOADING')""",
                    linkId, messageId, i, "https://www.tcbs.com.vn/" + i + ".pdf", "https://www.tcbs.com.vn/" + i + ".pdf");
            jdbc.update("insert into download_jobs (id, source_link_id, status, max_attempts) values (?, ?, 'PENDING', 3)",
                    UUID.randomUUID(), linkId);
        }
    }
}

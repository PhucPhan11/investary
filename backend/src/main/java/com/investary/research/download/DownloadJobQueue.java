package com.investary.research.download;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Database-backed job queue operations.
 * <p>
 * Claims use {@code FOR UPDATE SKIP LOCKED}, so concurrent pollers (threads or application instances)
 * never claim the same job. Each claim gets a fresh token; completion and failure only apply when the
 * token still matches, so a worker whose lease expired cannot overwrite the result of a newer attempt.
 */
@Repository
public class DownloadJobQueue {

    private final NamedParameterJdbcTemplate jdbc;

    public DownloadJobQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClaimedJob> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                update download_jobs
                set status = 'RUNNING', claimed_at = now(), claim_token = :token, attempts = attempts + 1, updated_at = now()
                where id in (
                    select id from download_jobs
                    where status = 'PENDING' and next_attempt_at <= now()
                    order by next_attempt_at, created_at
                    limit :limit
                    for update skip locked)
                returning id, source_link_id, attempts, max_attempts""",
                new MapSqlParameterSource().addValue("token", token).addValue("limit", limit),
                (rs, i) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_link_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        token));
    }

    /**
     * Re-queues jobs whose worker did not finish within the lease (e.g. the application stopped).
     * Jobs that already used all attempts are failed, together with their link.
     *
     * @return number of recovered jobs
     */
    public int recoverExpired(Duration lease) {
        return jdbc.update("""
                with expired as (
                    update download_jobs
                    set status = case when attempts >= max_attempts then 'FAILED' else 'PENDING' end,
                        claim_token = null, claimed_at = null, next_attempt_at = now(), updated_at = now(),
                        last_error = 'The previous attempt did not finish (worker stopped or timed out)'
                    where status = 'RUNNING' and claimed_at < now() - make_interval(secs => :leaseSeconds)
                    returning source_link_id, status, last_error)
                update source_links l
                set status = case when e.status = 'FAILED' then 'FAILED' else l.status end,
                    error_message = e.last_error
                from expired e
                where l.id = e.source_link_id""",
                new MapSqlParameterSource().addValue("leaseSeconds", (double) lease.toSeconds()));
    }

    public boolean complete(ClaimedJob job) {
        return jdbc.update("""
                update download_jobs
                set status = 'SUCCEEDED', claim_token = null, last_error = null, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job)) == 1;
    }

    public boolean retryLater(ClaimedJob job, String error, Instant nextAttemptAt) {
        return jdbc.update("""
                update download_jobs
                set status = 'PENDING', claim_token = null, claimed_at = null, last_error = :error,
                    next_attempt_at = :next, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("error", error).addValue("next", Timestamp.from(nextAttemptAt))) == 1;
    }

    public boolean fail(ClaimedJob job, String error) {
        return jdbc.update("""
                update download_jobs
                set status = 'FAILED', claim_token = null, last_error = :error, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("error", error)) == 1;
    }

    private static MapSqlParameterSource tokenParams(ClaimedJob job) {
        return new MapSqlParameterSource().addValue("id", job.jobId()).addValue("token", job.claimToken());
    }

    /**
     * @param attempts attempt number of this claim (1 for the first try)
     */
    public record ClaimedJob(UUID jobId, UUID linkId, int attempts, int maxAttempts, UUID claimToken) {
    }
}

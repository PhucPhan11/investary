package com.investary.automation.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;

/**
 * Persistent automation job queue, the same pattern as the download and indexing queues: claims use
 * {@code FOR UPDATE SKIP LOCKED} with a fresh claim token, and every result write only applies while the
 * token still matches, so a worker whose lease expired cannot overwrite a newer attempt.
 * <p>
 * The idempotency key makes enqueueing safe to repeat: an active job is left alone, a finished one is
 * re-opened (same row, attempts reset), so a stage never gets two jobs.
 */
@Repository
public class AutomationJobQueue {

    private static final String COLUMNS = """
            id, job_type, workflow_id, portfolio_id, idempotency_key, status, outcome, attempts, max_attempts, next_attempt_at,
            wait_deadline, started_at, completed_at, error_code, error_summary, processing_version, input_tokens, output_tokens,
            created_at, updated_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public AutomationJobQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the job, or re-opens a finished one with the same key. Active jobs are not touched.
     *
     * @return the job id
     */
    public UUID enqueue(UUID workflowId, JobType type, UUID portfolioId, String idempotencyKey, int maxAttempts, String version) {
        return jdbc.queryForObject("""
                insert into automation_jobs (id, job_type, workflow_id, portfolio_id, idempotency_key, status, attempts, max_attempts,
                                             next_attempt_at, processing_version)
                values (:id, :type, :workflowId, :portfolioId, :key, 'PENDING', 0, :maxAttempts, now(), :version)
                on conflict (idempotency_key) do update
                set status = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then 'PENDING' else automation_jobs.status end,
                    attempts = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then 0 else automation_jobs.attempts end,
                    max_attempts = excluded.max_attempts,
                    next_attempt_at = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then now()
                                           else automation_jobs.next_attempt_at end,
                    wait_deadline = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then null
                                         else automation_jobs.wait_deadline end,
                    outcome = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then null else automation_jobs.outcome end,
                    error_code = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then null else automation_jobs.error_code end,
                    error_summary = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then null
                                         else automation_jobs.error_summary end,
                    completed_at = case when automation_jobs.status in ('SUCCEEDED', 'FAILED') then null
                                        else automation_jobs.completed_at end,
                    processing_version = excluded.processing_version,
                    updated_at = now()
                returning id""",
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("type", type.name())
                        .addValue("workflowId", workflowId)
                        .addValue("portfolioId", portfolioId)
                        .addValue("key", idempotencyKey)
                        .addValue("maxAttempts", maxAttempts)
                        .addValue("version", version),
                UUID.class);
    }

    public List<ClaimedJob> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                update automation_jobs j
                set status = 'RUNNING', claimed_at = now(), claim_token = :token, updated_at = now(),
                    attempts = case when c.old_status = 'WAITING' then j.attempts else j.attempts + 1 end,
                    started_at = coalesce(j.started_at, now())
                from (select id, status as old_status from automation_jobs
                      where status in ('PENDING', 'WAITING') and next_attempt_at <= now()
                      order by next_attempt_at, created_at
                      limit :limit
                      for update skip locked) c
                where j.id = c.id
                returning j.id, j.job_type, j.workflow_id, j.portfolio_id, j.attempts, j.max_attempts, j.wait_deadline, c.old_status""",
                new MapSqlParameterSource().addValue("token", token).addValue("limit", limit),
                (rs, i) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        JobType.valueOf(rs.getString("job_type")),
                        rs.getObject("workflow_id", UUID.class),
                        rs.getObject("portfolio_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        instant(rs, "wait_deadline"),
                        "WAITING".equals(rs.getString("old_status")),
                        token));
    }

    /**
     * Re-queues jobs whose worker did not finish within the lease (e.g. the application stopped); jobs that
     * already used all attempts fail.
     *
     * @return number of recovered jobs
     */
    public int recoverExpired(Duration lease) {
        return jdbc.update("""
                update automation_jobs
                set status = case when attempts >= max_attempts then 'FAILED' else 'PENDING' end,
                    completed_at = case when attempts >= max_attempts then now() else null end,
                    claim_token = null, claimed_at = null, next_attempt_at = now(), updated_at = now(),
                    error_code = 'INTERRUPTED',
                    error_summary = 'The previous attempt did not finish (application stopped or timed out)'
                where status = 'RUNNING' and claimed_at < now() - make_interval(secs => :leaseSeconds)""",
                new MapSqlParameterSource().addValue("leaseSeconds", (double) lease.toSeconds()));
    }

    public boolean isClaimed(ClaimedJob job) {
        Boolean claimed = jdbc.queryForObject(
                "select exists(select 1 from automation_jobs where id = :id and claim_token = :token and status = 'RUNNING')",
                tokenParams(job), Boolean.class);
        return Boolean.TRUE.equals(claimed);
    }

    public boolean complete(ClaimedJob job, String outcome, Integer inputTokens, Integer outputTokens) {
        return jdbc.update("""
                update automation_jobs
                set status = 'SUCCEEDED', outcome = :outcome, claim_token = null, claimed_at = null, wait_deadline = null,
                    error_code = null, error_summary = null, completed_at = now(), updated_at = now(),
                    input_tokens = coalesce(:inputTokens, input_tokens), output_tokens = coalesce(:outputTokens, output_tokens)
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("outcome", outcome).addValue("inputTokens", inputTokens)
                        .addValue("outputTokens", outputTokens)) == 1;
    }

    /** Waits for work done elsewhere; does not use an attempt. The caller decides the deadline. */
    public boolean waitUntil(ClaimedJob job, Instant next, Instant deadline) {
        return jdbc.update("""
                update automation_jobs
                set status = 'WAITING', claim_token = null, claimed_at = null, next_attempt_at = :next,
                    wait_deadline = :deadline, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("next", Timestamp.from(next)).addValue("deadline", Timestamp.from(deadline))) == 1;
    }

    public boolean retryLater(ClaimedJob job, String errorCode, String errorSummary, Instant next) {
        return jdbc.update("""
                update automation_jobs
                set status = 'PENDING', claim_token = null, claimed_at = null, wait_deadline = null, next_attempt_at = :next,
                    error_code = :code, error_summary = :summary, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("code", errorCode).addValue("summary", errorSummary)
                        .addValue("next", Timestamp.from(next))) == 1;
    }

    public boolean fail(ClaimedJob job, String errorCode, String errorSummary) {
        return jdbc.update("""
                update automation_jobs
                set status = 'FAILED', claim_token = null, claimed_at = null, wait_deadline = null, error_code = :code,
                    error_summary = :summary, completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("code", errorCode).addValue("summary", errorSummary)) == 1;
    }

    /**
     * Manual retry of a failed job: same row, attempts reset.
     *
     * @return false if the job is not FAILED
     */
    public boolean resetFailed(UUID id) {
        return jdbc.update("""
                update automation_jobs
                set status = 'PENDING', attempts = 0, next_attempt_at = now(), wait_deadline = null, outcome = null,
                    error_code = null, error_summary = null, completed_at = null, updated_at = now()
                where id = :id and status = 'FAILED'""",
                new MapSqlParameterSource("id", id)) == 1;
    }

    public Optional<JobRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from automation_jobs where id = :id",
                new MapSqlParameterSource("id", id), AutomationJobQueue::row).stream().findFirst();
    }

    public List<JobRow> forWorkflow(UUID workflowId) {
        return jdbc.query("select " + COLUMNS + " from automation_jobs where workflow_id = :id order by created_at, job_type",
                new MapSqlParameterSource("id", workflowId), AutomationJobQueue::row);
    }

    public List<JobRow> list(JobStatus status, JobType type, UUID workflowId, int page, int size) {
        return jdbc.query("select " + COLUMNS + " from automation_jobs where " + FILTER
                        + " order by created_at desc, id limit :limit offset :offset",
                filterParams(status, type, workflowId).addValue("limit", size).addValue("offset", (long) page * size),
                AutomationJobQueue::row);
    }

    public long count(JobStatus status, JobType type, UUID workflowId) {
        Long count = jdbc.queryForObject("select count(*) from automation_jobs where " + FILTER,
                filterParams(status, type, workflowId), Long.class);
        return count == null ? 0 : count;
    }

    public boolean hasActiveJobs(UUID workflowId) {
        Boolean active = jdbc.queryForObject("""
                select exists(select 1 from automation_jobs
                              where workflow_id = :id and status in ('PENDING', 'RUNNING', 'WAITING'))""",
                new MapSqlParameterSource("id", workflowId), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /** New assessments created by automation since the given instant (the daily cap). */
    public long countCreatedAssessmentsSince(Instant since) {
        Long count = jdbc.queryForObject("""
                select count(*) from automation_jobs
                where job_type = 'ASSESS' and status = 'SUCCEEDED' and outcome = 'CREATED' and completed_at >= :since""",
                new MapSqlParameterSource("since", Timestamp.from(since)), Long.class);
        return count == null ? 0 : count;
    }

    private static final String FILTER = """
            (cast(:status as varchar) is null or status = :status)
              and (cast(:type as varchar) is null or job_type = :type)
              and (cast(:workflowId as uuid) is null or workflow_id = :workflowId)""";

    private static MapSqlParameterSource filterParams(JobStatus status, JobType type, UUID workflowId) {
        return new MapSqlParameterSource()
                .addValue("status", status == null ? null : status.name())
                .addValue("type", type == null ? null : type.name())
                .addValue("workflowId", workflowId);
    }

    private static MapSqlParameterSource tokenParams(ClaimedJob job) {
        return new MapSqlParameterSource().addValue("id", job.id()).addValue("token", job.claimToken());
    }

    private static JobRow row(ResultSet rs, int i) throws SQLException {
        return new JobRow(
                rs.getObject("id", UUID.class),
                JobType.valueOf(rs.getString("job_type")),
                rs.getObject("workflow_id", UUID.class),
                rs.getObject("portfolio_id", UUID.class),
                rs.getString("idempotency_key"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getString("outcome"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                instant(rs, "next_attempt_at"),
                instant(rs, "wait_deadline"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getString("processing_version"),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * @param attempts     attempt number of this claim (a resumed wait keeps the previous number)
     * @param waitDeadline when a waiting job gives up, or null before its first wait
     * @param resumed      true when this claim continues a wait, false for a fresh attempt
     */
    public record ClaimedJob(UUID id, JobType type, UUID workflowId, UUID portfolioId, int attempts, int maxAttempts,
                             Instant waitDeadline, boolean resumed, UUID claimToken) {
    }

    public record JobRow(UUID id, JobType type, UUID workflowId, UUID portfolioId, String idempotencyKey, JobStatus status,
                         String outcome, int attempts, int maxAttempts, Instant nextAttemptAt, Instant waitDeadline,
                         Instant startedAt, Instant completedAt, String errorCode, String errorSummary, String processingVersion,
                         Integer inputTokens, Integer outputTokens, Instant createdAt, Instant updatedAt) {
    }
}

package com.investary.automation.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
                with claimed as (
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
                    returning j.id, j.job_type, j.workflow_id, j.portfolio_id, j.attempts, j.max_attempts, j.wait_deadline,
                              c.old_status),
                attempt as (
                    insert into automation_job_attempts (id, job_id, attempt, trigger_type, started_at, result)
                    select gen_random_uuid(), id, attempts, 'AUTO', now(), 'RUNNING' from claimed where old_status <> 'WAITING')
                select * from claimed""",
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
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("leaseSeconds", (double) lease.toSeconds());
        jdbc.update("""
                update automation_job_attempts a
                set result = 'LEASE_EXPIRED', finished_at = now(), error_code = 'INTERRUPTED',
                    error_summary = 'The attempt did not finish (application stopped or timed out)'
                from automation_jobs j
                where a.job_id = j.id and a.finished_at is null and a.result in ('RUNNING', 'WAITING')
                  and j.status = 'RUNNING' and j.claimed_at < now() - make_interval(secs => :leaseSeconds)""", params);
        return jdbc.update("""
                update automation_jobs
                set status = case when attempts >= max_attempts then 'FAILED' else 'PENDING' end,
                    completed_at = case when attempts >= max_attempts then now() else null end,
                    claim_token = null, claimed_at = null, next_attempt_at = now(), updated_at = now(),
                    error_code = 'INTERRUPTED',
                    error_summary = 'The previous attempt did not finish (application stopped or timed out)'
                where status = 'RUNNING' and claimed_at < now() - make_interval(secs => :leaseSeconds)""", params);
    }

    public boolean isClaimed(ClaimedJob job) {
        Boolean claimed = jdbc.queryForObject(
                "select exists(select 1 from automation_jobs where id = :id and claim_token = :token and status = 'RUNNING')",
                tokenParams(job), Boolean.class);
        return Boolean.TRUE.equals(claimed);
    }

    public boolean complete(ClaimedJob job, String outcome, Integer inputTokens, Integer outputTokens) {
        return guarded("""
                update automation_jobs
                set status = 'SUCCEEDED', outcome = :outcome, claim_token = null, claimed_at = null, wait_deadline = null,
                    error_code = null, error_summary = null, completed_at = now(), updated_at = now(),
                    input_tokens = coalesce(:inputTokens, input_tokens), output_tokens = coalesce(:outputTokens, output_tokens)
                where id = :id and claim_token = :token and status = 'RUNNING'
                returning id""", "SUCCEEDED", true,
                tokenParams(job).addValue("outcome", outcome).addValue("inputTokens", inputTokens)
                        .addValue("outputTokens", outputTokens).addValue("code", null).addValue("summary", null));
    }

    /** Waits for work done elsewhere; does not use an attempt. The caller decides the deadline. */
    public boolean waitUntil(ClaimedJob job, Instant next, Instant deadline) {
        return guarded("""
                update automation_jobs
                set status = 'WAITING', claim_token = null, claimed_at = null, next_attempt_at = :next,
                    wait_deadline = :deadline, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'
                returning id""", "WAITING", false,
                tokenParams(job).addValue("next", Timestamp.from(next)).addValue("deadline", Timestamp.from(deadline))
                        .addValue("code", null).addValue("summary", null));
    }

    public boolean retryLater(ClaimedJob job, String errorCode, String errorSummary, Instant next) {
        return guarded("""
                update automation_jobs
                set status = 'PENDING', claim_token = null, claimed_at = null, wait_deadline = null, next_attempt_at = :next,
                    error_code = :code, error_summary = :summary, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'
                returning id""", "RETRY_SCHEDULED", true,
                tokenParams(job).addValue("code", errorCode).addValue("summary", errorSummary)
                        .addValue("next", Timestamp.from(next)));
    }

    public boolean fail(ClaimedJob job, String errorCode, String errorSummary) {
        return guarded("""
                update automation_jobs
                set status = 'FAILED', claim_token = null, claimed_at = null, wait_deadline = null, error_code = :code,
                    error_summary = :summary, completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'
                returning id""", "FAILED", true,
                tokenParams(job).addValue("code", errorCode).addValue("summary", errorSummary));
    }

    /**
     * Runs a token-guarded job update and, only if it applied, records the result on the job's open attempt
     * in the same statement.
     *
     * @param jobUpdate update of {@code automation_jobs} ending in {@code returning id}
     * @param finished  whether the attempt ends here (false while it waits on other work)
     */
    private boolean guarded(String jobUpdate, String result, boolean finished, MapSqlParameterSource params) {
        Integer updated = jdbc.queryForObject("with job as (" + jobUpdate + """
                ), attempt as (
                    update automation_job_attempts a
                    set result = :result, finished_at = case when :finished then now() else null end,
                        error_code = :code, error_summary = :summary
                    where a.id = (select id from automation_job_attempts where job_id = :id and finished_at is null
                                  order by started_at desc limit 1)
                      and exists (select 1 from job))
                select count(*) from job""", params.addValue("result", result).addValue("finished", finished), Integer.class);
        return updated != null && updated == 1;
    }

    /**
     * Manual retry of a failed job: same row, attempts reset.
     *
     * @return false if the job is not FAILED
     */
    public boolean resetFailed(UUID id) {
        Integer updated = jdbc.queryForObject("""
                with job as (
                    update automation_jobs
                    set status = 'PENDING', attempts = 0, next_attempt_at = now(), wait_deadline = null, outcome = null,
                        error_code = null, error_summary = null, completed_at = null, updated_at = now()
                    where id = :id and status = 'FAILED'
                    returning id),
                attempt as (
                    insert into automation_job_attempts (id, job_id, attempt, trigger_type, started_at, finished_at, result)
                    select gen_random_uuid(), id, 0, 'MANUAL_RETRY', now(), now(), 'RETRY_REQUESTED' from job)
                select count(*) from job""",
                new MapSqlParameterSource("id", id), Integer.class);
        return updated != null && updated == 1;
    }

    public int countManualRetries(UUID jobId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from automation_job_attempts where job_id = :id and trigger_type = 'MANUAL_RETRY'",
                new MapSqlParameterSource("id", jobId), Integer.class);
        return count == null ? 0 : count;
    }

    /** Attempt history of a job, oldest first. */
    public List<AttemptRow> attempts(UUID jobId) {
        return jdbc.query("""
                select attempt, trigger_type, started_at, finished_at, result, error_code, error_summary
                from automation_job_attempts where job_id = :id order by started_at, attempt""",
                new MapSqlParameterSource("id", jobId),
                (rs, i) -> new AttemptRow(rs.getInt("attempt"), rs.getString("trigger_type"), instant(rs, "started_at"),
                        instant(rs, "finished_at"), rs.getString("result"), rs.getString("error_code"), rs.getString("error_summary")));
    }

    public Optional<JobRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from automation_jobs where id = :id",
                new MapSqlParameterSource("id", id), AutomationJobQueue::row).stream().findFirst();
    }

    public List<JobRow> forWorkflow(UUID workflowId) {
        return jdbc.query("select " + COLUMNS + " from automation_jobs where workflow_id = :id order by created_at, job_type",
                new MapSqlParameterSource("id", workflowId), AutomationJobQueue::row);
    }

    public List<JobRow> list(JobFilter filter, int page, int size) {
        return jdbc.query("select " + J_COLUMNS + " from automation_jobs j join research_workflows w on w.id = j.workflow_id where "
                        + FILTER + " order by j.created_at desc, j.id limit :limit offset :offset",
                filterParams(filter).addValue("limit", size).addValue("offset", (long) page * size),
                AutomationJobQueue::row);
    }

    public long count(JobFilter filter) {
        Long count = jdbc.queryForObject("select count(*) from automation_jobs j join research_workflows w on w.id = j.workflow_id where "
                + FILTER, filterParams(filter), Long.class);
        return count == null ? 0 : count;
    }

    /** Jobs in the given statuses now (the backlog); with {@code olderThan}, only those not updated since then. */
    public long countInStatus(List<JobStatus> statuses, Instant olderThan) {
        Long count = jdbc.queryForObject("""
                select count(*) from automation_jobs
                where status in (:statuses) and (cast(:olderThan as timestamptz) is null or updated_at < :olderThan)""",
                new MapSqlParameterSource("statuses", statuses.stream().map(Enum::name).toList())
                        .addValue("olderThan", olderThan == null ? null : Timestamp.from(olderThan)), Long.class);
        return count == null ? 0 : count;
    }

    /** Jobs that ended FAILED in [from, to). */
    public long countFailed(Instant from, Instant to) {
        Long count = jdbc.queryForObject(
                "select count(*) from automation_jobs where status = 'FAILED' and completed_at >= :from and completed_at < :to",
                new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)), Long.class);
        return count == null ? 0 : count;
    }

    /** Latest time a job finished, or null. */
    public Instant lastCompletedAt() {
        Timestamp last = jdbc.queryForObject("select max(completed_at) from automation_jobs", new MapSqlParameterSource(), Timestamp.class);
        return last == null ? null : last.toInstant();
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

    private static final String J_COLUMNS = String.join(", ", Arrays.stream(COLUMNS.split(",")).map(c -> "j." + c.strip()).toList());

    private static final String FILTER = """
            (cast(:status as varchar) is null or j.status = :status)
              and (cast(:type as varchar) is null or j.job_type = :type)
              and (cast(:workflowId as uuid) is null or j.workflow_id = :workflowId)
              and (cast(:from as timestamptz) is null or j.created_at >= :from)
              and (cast(:to as timestamptz) is null or j.created_at < :to)
              and (cast(:sourceType as varchar) is null or w.source_type = :sourceType)
              and (cast(:ruleId as uuid) is null or exists (select 1 from gmail_messages g
                                                            where g.source_message_id = w.source_message_id
                                                              and g.source_rule_id = :ruleId))""";

    private static MapSqlParameterSource filterParams(JobFilter filter) {
        return new MapSqlParameterSource()
                .addValue("status", filter.status() == null ? null : filter.status().name())
                .addValue("type", filter.type() == null ? null : filter.type().name())
                .addValue("workflowId", filter.workflowId())
                .addValue("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                .addValue("to", filter.to() == null ? null : Timestamp.from(filter.to()))
                .addValue("sourceType", filter.sourceType())
                .addValue("ruleId", filter.ruleId());
    }

    /**
     * Job list filter; null fields do not filter.
     *
     * @param from       created at or after
     * @param to         created before
     * @param sourceType {@code EMAIL} or {@code DOCUMENT}
     * @param ruleId     Gmail source rule that matched the email
     */
    public record JobFilter(JobStatus status, JobType type, UUID workflowId, Instant from, Instant to, String sourceType, UUID ruleId) {
    }

    public record AttemptRow(int attempt, String trigger, Instant startedAt, Instant finishedAt, String result, String errorCode,
                             String errorSummary) {
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

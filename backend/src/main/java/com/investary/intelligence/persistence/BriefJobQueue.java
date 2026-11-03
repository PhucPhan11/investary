package com.investary.intelligence.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.intelligence.domain.BriefJobOutcome;
import com.investary.intelligence.domain.BriefJobStatus;
import com.investary.intelligence.domain.BriefTrigger;

/**
 * Persistent brief generation queue, the same pattern as {@code automation_jobs}: claims use
 * {@code FOR UPDATE SKIP LOCKED} with a fresh claim token and every result write only applies while the
 * token still matches, so a worker whose lease expired cannot overwrite a newer attempt.
 * <p>
 * The idempotency key prevents duplicate generation: a scheduled key is inserted at most once; a manual or
 * regeneration key leaves an active job alone and re-opens a finished one (same row).
 */
@Repository
public class BriefJobQueue {

    private static final String COLUMNS = """
            id, trigger_type, portfolio_id, period_start, period_end, zone, business_date, partial_period, previous_brief_id,
            config_version, idempotency_key, status, outcome, brief_id, attempts, max_attempts, next_attempt_at, started_at,
            completed_at, error_code, error_summary, input_tokens, output_tokens, created_at, updated_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public BriefJobQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param reopen false: insert once, never touch an existing job (scheduled runs); true: re-open a
     *               finished job with the same key (manual generation and regeneration)
     * @return the job id, and whether this call created or re-opened it
     */
    public Enqueued enqueue(JobRequest request, String idempotencyKey, int maxAttempts, boolean reopen) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", UUID.randomUUID())
                .addValue("trigger", request.trigger().name())
                .addValue("portfolioId", request.portfolioId())
                .addValue("start", Timestamp.from(request.periodStart()))
                .addValue("end", Timestamp.from(request.periodEnd()))
                .addValue("zone", request.zone())
                .addValue("businessDate", Date.valueOf(request.businessDate()))
                .addValue("partial", request.partialPeriod())
                .addValue("previous", request.previousBriefId())
                .addValue("config", request.configVersion())
                .addValue("key", idempotencyKey)
                .addValue("maxAttempts", maxAttempts);
        String insert = """
                insert into brief_jobs (id, trigger_type, portfolio_id, period_start, period_end, zone, business_date, partial_period,
                                        previous_brief_id, config_version, idempotency_key, status, attempts, max_attempts, next_attempt_at)
                values (:id, :trigger, :portfolioId, :start, :end, :zone, :businessDate, :partial, :previous, :config, :key, 'PENDING', 0,
                        :maxAttempts, now())
                """;
        if (!reopen) {
            List<UUID> created = jdbc.queryForList(insert + " on conflict (idempotency_key) do nothing returning id", params, UUID.class);
            if (!created.isEmpty()) {
                return new Enqueued(created.getFirst(), true);
            }
            return new Enqueued(jdbc.queryForObject("select id from brief_jobs where idempotency_key = :key", params, UUID.class), false);
        }
        return jdbc.queryForObject(insert + """
                 on conflict (idempotency_key) do update
                set status = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then 'PENDING' else brief_jobs.status end,
                    attempts = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then 0 else brief_jobs.attempts end,
                    next_attempt_at = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then now() else brief_jobs.next_attempt_at end,
                    outcome = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.outcome end,
                    brief_id = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.brief_id end,
                    error_code = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.error_code end,
                    error_summary = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.error_summary end,
                    completed_at = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.completed_at end,
                    input_tokens = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.input_tokens end,
                    output_tokens = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then null else brief_jobs.output_tokens end,
                    period_end = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then excluded.period_end else brief_jobs.period_end end,
                    partial_period = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then excluded.partial_period
                                          else brief_jobs.partial_period end,
                    config_version = case when brief_jobs.status in ('SUCCEEDED', 'FAILED') then excluded.config_version
                                          else brief_jobs.config_version end,
                    max_attempts = excluded.max_attempts,
                    updated_at = now()
                returning id, (xmax = 0) as created""",
                params, (rs, i) -> new Enqueued(rs.getObject("id", UUID.class), rs.getBoolean("created")));
    }

    public List<ClaimedJob> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                update brief_jobs j
                set status = 'RUNNING', claimed_at = now(), claim_token = :token, updated_at = now(), attempts = j.attempts + 1,
                    started_at = coalesce(j.started_at, now())
                from (select id from brief_jobs
                      where status = 'PENDING' and next_attempt_at <= now()
                      order by next_attempt_at, created_at
                      limit :limit
                      for update skip locked) c
                where j.id = c.id
                returning j.id, j.trigger_type, j.portfolio_id, j.period_start, j.period_end, j.zone, j.business_date, j.partial_period,
                          j.previous_brief_id, j.config_version, j.attempts, j.max_attempts""",
                new MapSqlParameterSource().addValue("token", token).addValue("limit", limit),
                (rs, i) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        BriefTrigger.valueOf(rs.getString("trigger_type")),
                        rs.getObject("portfolio_id", UUID.class),
                        instant(rs, "period_start"),
                        instant(rs, "period_end"),
                        rs.getString("zone"),
                        rs.getDate("business_date").toLocalDate(),
                        rs.getBoolean("partial_period"),
                        rs.getObject("previous_brief_id", UUID.class),
                        rs.getString("config_version"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
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
                update brief_jobs
                set status = case when attempts >= max_attempts then 'FAILED' else 'PENDING' end,
                    completed_at = case when attempts >= max_attempts then now() else null end,
                    claim_token = null, claimed_at = null, next_attempt_at = now(), updated_at = now(),
                    error_code = 'INTERRUPTED',
                    error_summary = 'The previous attempt did not finish (application stopped or timed out)'
                where status = 'RUNNING' and claimed_at < now() - make_interval(secs => :leaseSeconds)""",
                new MapSqlParameterSource().addValue("leaseSeconds", (double) lease.toSeconds()));
    }

    /** Locks the job row while the claim is still valid; call inside the transaction that writes the result. */
    public boolean lockClaimed(ClaimedJob job) {
        return !jdbc.queryForList("select id from brief_jobs where id = :id and claim_token = :token and status = 'RUNNING' for update",
                tokenParams(job), UUID.class).isEmpty();
    }

    public boolean complete(ClaimedJob job, BriefJobOutcome outcome, UUID briefId, Integer inputTokens, Integer outputTokens) {
        return jdbc.update("""
                update brief_jobs
                set status = 'SUCCEEDED', outcome = :outcome, brief_id = :briefId, claim_token = null, claimed_at = null,
                    error_code = null, error_summary = null, completed_at = now(), updated_at = now(),
                    input_tokens = :inputTokens, output_tokens = :outputTokens
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("outcome", outcome.name()).addValue("briefId", briefId)
                        .addValue("inputTokens", inputTokens).addValue("outputTokens", outputTokens)) == 1;
    }

    public boolean retryLater(ClaimedJob job, String errorCode, String errorSummary, Instant next) {
        return jdbc.update("""
                update brief_jobs
                set status = 'PENDING', claim_token = null, claimed_at = null, next_attempt_at = :next,
                    error_code = :code, error_summary = :summary, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("code", errorCode).addValue("summary", errorSummary)
                        .addValue("next", Timestamp.from(next))) == 1;
    }

    public boolean fail(ClaimedJob job, String errorCode, String errorSummary, UUID briefId) {
        return jdbc.update("""
                update brief_jobs
                set status = 'FAILED', claim_token = null, claimed_at = null, error_code = :code, error_summary = :summary,
                    brief_id = :briefId, completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                tokenParams(job).addValue("code", errorCode).addValue("summary", errorSummary).addValue("briefId", briefId)) == 1;
    }

    /**
     * Manual retry of a failed job: same row and idempotency key, attempts reset.
     *
     * @return false if the job is not FAILED (one conditional update, so concurrent retries cannot both apply)
     */
    public boolean resetFailed(UUID id) {
        return jdbc.update("""
                update brief_jobs
                set status = 'PENDING', attempts = 0, next_attempt_at = now(), outcome = null, error_code = null,
                    error_summary = null, completed_at = null, updated_at = now()
                where id = :id and status = 'FAILED'""", new MapSqlParameterSource("id", id)) == 1;
    }

    /** Jobs created in [from, to) with an optional status, newest first. */
    public List<JobRow> list(BriefJobStatus status, Instant from, Instant to, int page, int size) {
        return jdbc.query("select " + COLUMNS + " from brief_jobs where " + FILTER + " order by created_at desc, id limit :limit offset :offset",
                filter(status, from, to).addValue("limit", size).addValue("offset", (long) page * size), BriefJobQueue::row);
    }

    public long count(BriefJobStatus status, Instant from, Instant to) {
        Long count = jdbc.queryForObject("select count(*) from brief_jobs where " + FILTER, filter(status, from, to), Long.class);
        return count == null ? 0 : count;
    }

    /** Jobs that ended FAILED in [from, to). */
    public long countFailed(Instant from, Instant to) {
        Long count = jdbc.queryForObject(
                "select count(*) from brief_jobs where status = 'FAILED' and completed_at >= :from and completed_at < :to",
                new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)), Long.class);
        return count == null ? 0 : count;
    }

    /** Jobs PENDING or RUNNING now. */
    public long countActive() {
        Long count = jdbc.queryForObject("select count(*) from brief_jobs where status in ('PENDING', 'RUNNING')",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /** The most recently finished job, if any. */
    public Optional<JobRow> latestFinished() {
        return jdbc.query("select " + COLUMNS + " from brief_jobs where status in ('SUCCEEDED', 'FAILED') order by completed_at desc, id limit 1",
                new MapSqlParameterSource(), BriefJobQueue::row).stream().findFirst();
    }

    private static final String FILTER = """
            (cast(:status as varchar) is null or status = :status)
              and (cast(:from as timestamptz) is null or created_at >= :from)
              and (cast(:to as timestamptz) is null or created_at < :to)""";

    private static MapSqlParameterSource filter(BriefJobStatus status, Instant from, Instant to) {
        return new MapSqlParameterSource()
                .addValue("status", status == null ? null : status.name())
                .addValue("from", from == null ? null : Timestamp.from(from))
                .addValue("to", to == null ? null : Timestamp.from(to));
    }

    public Optional<JobRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from brief_jobs where id = :id",
                new MapSqlParameterSource("id", id), BriefJobQueue::row).stream().findFirst();
    }

    private static MapSqlParameterSource tokenParams(ClaimedJob job) {
        return new MapSqlParameterSource().addValue("id", job.id()).addValue("token", job.claimToken());
    }

    private static JobRow row(ResultSet rs, int i) throws SQLException {
        String outcome = rs.getString("outcome");
        return new JobRow(
                rs.getObject("id", UUID.class),
                BriefTrigger.valueOf(rs.getString("trigger_type")),
                rs.getObject("portfolio_id", UUID.class),
                instant(rs, "period_start"),
                instant(rs, "period_end"),
                rs.getString("zone"),
                rs.getDate("business_date").toLocalDate(),
                rs.getBoolean("partial_period"),
                rs.getObject("previous_brief_id", UUID.class),
                rs.getString("config_version"),
                BriefJobStatus.valueOf(rs.getString("status")),
                outcome == null ? null : BriefJobOutcome.valueOf(outcome),
                rs.getObject("brief_id", UUID.class),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                instant(rs, "next_attempt_at"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * What to generate. {@code portfolioId} is null for a general brief (no portfolio).
     */
    public record JobRequest(BriefTrigger trigger, UUID portfolioId, Instant periodStart, Instant periodEnd, String zone,
                             LocalDate businessDate, boolean partialPeriod, UUID previousBriefId, String configVersion) {
    }

    /**
     * @param created true when this call inserted the job or re-opened a finished one
     */
    public record Enqueued(UUID id, boolean created) {
    }

    public record ClaimedJob(UUID id, BriefTrigger trigger, UUID portfolioId, Instant periodStart, Instant periodEnd, String zone,
                             LocalDate businessDate, boolean partialPeriod, UUID previousBriefId, String configVersion, int attempts,
                             int maxAttempts, UUID claimToken) {
    }

    public record JobRow(UUID id, BriefTrigger trigger, UUID portfolioId, Instant periodStart, Instant periodEnd, String zone,
                         LocalDate businessDate, boolean partialPeriod, UUID previousBriefId, String configVersion, BriefJobStatus status,
                         BriefJobOutcome outcome, UUID briefId, int attempts, int maxAttempts, Instant nextAttemptAt, Instant startedAt,
                         Instant completedAt, String errorCode, String errorSummary, Integer inputTokens, Integer outputTokens,
                         Instant createdAt) {
    }
}

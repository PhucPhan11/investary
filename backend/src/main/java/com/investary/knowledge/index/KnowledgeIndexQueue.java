package com.investary.knowledge.index;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.knowledge.KnowledgeSourceType;

/**
 * Index state and job queue in {@code knowledge_sources}, following the {@code download_jobs} design:
 * claims use {@code FOR UPDATE SKIP LOCKED} and a fresh claim token, and every state change after a
 * claim only applies while the token still matches. One row per source plus the token means two
 * workers can never index the same source at the same time.
 */
@Repository
public class KnowledgeIndexQueue {

    private final NamedParameterJdbcTemplate jdbc;

    public KnowledgeIndexQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Starts tracking documents and emails that have no index state yet (new imports, downloads and
     * Phase 1/2 data). Reads committed rows only, so a source is never indexed before it is persisted.
     *
     * @return number of sources added
     */
    public int trackMissing(int maxAttempts) {
        var params = new MapSqlParameterSource("maxAttempts", maxAttempts);
        int documents = jdbc.update("""
                insert into knowledge_sources (id, source_type, document_id, status, max_attempts)
                select gen_random_uuid(), 'DOCUMENT', d.id, 'PENDING', :maxAttempts
                from document d
                where not exists (select 1 from knowledge_sources k where k.document_id = d.id)
                on conflict do nothing""", params);
        int emails = jdbc.update("""
                insert into knowledge_sources (id, source_type, source_message_id, status, max_attempts)
                select gen_random_uuid(), 'EMAIL', m.id, 'PENDING', :maxAttempts
                from source_messages m
                where not exists (select 1 from knowledge_sources k where k.source_message_id = m.id)
                on conflict do nothing""", params);
        return documents + emails;
    }

    /**
     * Queues one source for (re)indexing. A source that is being processed right now is left alone.
     *
     * @return the knowledge source id
     */
    public UUID request(KnowledgeSourceType type, UUID sourceId, int maxAttempts) {
        String column = column(type);
        var params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("type", type.name())
                .addValue("sourceId", sourceId)
                .addValue("maxAttempts", maxAttempts);
        List<UUID> ids = jdbc.queryForList("""
                insert into knowledge_sources (id, source_type, %1$s, status, max_attempts)
                values (:id, :type, :sourceId, 'PENDING', :maxAttempts)
                on conflict (%1$s) do update
                set status = 'PENDING', status_reason = null, attempts = 0, max_attempts = :maxAttempts,
                    next_attempt_at = now(), last_error = null, requested_at = now(), updated_at = now()
                where knowledge_sources.status <> 'PROCESSING'
                returning id""".formatted(column), params, UUID.class);
        if (!ids.isEmpty()) {
            return ids.getFirst();
        }
        return jdbc.queryForObject("select id from knowledge_sources where %s = :sourceId".formatted(column), params, UUID.class);
    }

    /**
     * @return number of sources queued again
     */
    public int requeue(ReindexScope scope, String activeVersion, int maxAttempts) {
        String condition = switch (scope) {
            case ALL -> "status <> 'PROCESSING'";
            case FAILED -> "status = 'FAILED'";
            case STALE -> "status in ('INDEXED', 'SKIPPED') and index_version is distinct from :version";
            case MISSING -> throw new IllegalArgumentException("MISSING is handled by trackMissing");
        };
        return jdbc.update("""
                update knowledge_sources
                set status = 'PENDING', status_reason = null, attempts = 0, max_attempts = :maxAttempts,
                    next_attempt_at = now(), last_error = null, requested_at = now(), updated_at = now()
                where""" + " " + condition,
                new MapSqlParameterSource().addValue("version", activeVersion).addValue("maxAttempts", maxAttempts));
    }

    public List<ClaimedSource> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                update knowledge_sources
                set status = 'PROCESSING', claimed_at = now(), claim_token = :token, attempts = attempts + 1,
                    started_at = now(), updated_at = now()
                where id in (
                    select id from knowledge_sources
                    where status = 'PENDING' and next_attempt_at <= now()
                    order by next_attempt_at, requested_at
                    limit :limit
                    for update skip locked)
                returning id, source_type, document_id, source_message_id, attempts, max_attempts, index_version, content_sha256""",
                new MapSqlParameterSource().addValue("token", token).addValue("limit", limit),
                (rs, i) -> {
                    KnowledgeSourceType type = KnowledgeSourceType.valueOf(rs.getString("source_type"));
                    UUID sourceId = rs.getObject(type == KnowledgeSourceType.DOCUMENT ? "document_id" : "source_message_id", UUID.class);
                    return new ClaimedSource(rs.getObject("id", UUID.class), type, sourceId, rs.getInt("attempts"),
                            rs.getInt("max_attempts"), token, rs.getString("index_version"), rs.getString("content_sha256"));
                });
    }

    /**
     * Re-queues sources whose worker did not finish within the lease; those out of attempts fail.
     */
    public int recoverExpired(Duration lease) {
        return jdbc.update("""
                update knowledge_sources
                set status = case when attempts >= max_attempts then 'FAILED' else 'PENDING' end,
                    claim_token = null, claimed_at = null, next_attempt_at = now(), updated_at = now(),
                    last_error = 'The previous attempt did not finish (worker stopped or timed out)'
                where status = 'PROCESSING' and claimed_at < now() - make_interval(secs => :leaseSeconds)""",
                new MapSqlParameterSource("leaseSeconds", (double) lease.toSeconds()));
    }

    public boolean skip(ClaimedSource job, String reason, String version, Integer pageCount) {
        return jdbc.update("""
                update knowledge_sources
                set status = 'SKIPPED', status_reason = :reason, index_version = :version, page_count = :pages,
                    chunk_count = 0, content_sha256 = null, claim_token = null, last_error = null,
                    completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'PROCESSING'""",
                tokenParams(job).addValue("reason", reason).addValue("version", version).addValue("pages", pageCount)) == 1;
    }

    public boolean retryLater(ClaimedSource job, String error, Instant nextAttemptAt) {
        return jdbc.update("""
                update knowledge_sources
                set status = 'PENDING', claim_token = null, claimed_at = null, last_error = :error,
                    next_attempt_at = :next, updated_at = now()
                where id = :id and claim_token = :token and status = 'PROCESSING'""",
                tokenParams(job).addValue("error", error).addValue("next", Timestamp.from(nextAttemptAt))) == 1;
    }

    /**
     * Marks the job failed. Chunks from an earlier successful index stay in the table but are not
     * searched, because search only uses INDEXED sources; a retry or reindex replaces them.
     */
    public boolean fail(ClaimedSource job, String error) {
        return jdbc.update("""
                update knowledge_sources
                set status = 'FAILED', claim_token = null, last_error = :error, completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'PROCESSING'""",
                tokenParams(job).addValue("error", error)) == 1;
    }

    /**
     * Marks an unchanged source indexed again without touching its chunks (idempotent reindex).
     */
    public boolean confirmUnchanged(ClaimedSource job) {
        return jdbc.update("""
                update knowledge_sources
                set status = 'INDEXED', status_reason = null, claim_token = null, last_error = null,
                    completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'PROCESSING'""",
                tokenParams(job)) == 1;
    }

    public Optional<KnowledgeJob> find(UUID id) {
        return jdbc.query(SELECT_JOB + " where id = :id", new MapSqlParameterSource("id", id), KnowledgeIndexQueue::mapJob)
                .stream().findFirst();
    }

    public Optional<KnowledgeJob> findBySource(KnowledgeSourceType type, UUID sourceId) {
        return jdbc.query(SELECT_JOB + " where " + column(type) + " = :sourceId", new MapSqlParameterSource("sourceId", sourceId),
                KnowledgeIndexQueue::mapJob).stream().findFirst();
    }

    private static final String SELECT_JOB = """
            select id, source_type, document_id, source_message_id, status, status_reason, index_version, chunk_count,
                   page_count, attempts, max_attempts, last_error, requested_at, started_at, completed_at, next_attempt_at
            from knowledge_sources""";

    private static KnowledgeJob mapJob(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        KnowledgeSourceType type = KnowledgeSourceType.valueOf(rs.getString("source_type"));
        UUID sourceId = rs.getObject(type == KnowledgeSourceType.DOCUMENT ? "document_id" : "source_message_id", UUID.class);
        Integer pages = rs.getObject("page_count", Integer.class);
        return new KnowledgeJob(rs.getObject("id", UUID.class), type, sourceId,
                KnowledgeSourceStatus.valueOf(rs.getString("status")), rs.getString("status_reason"),
                rs.getString("index_version"), rs.getInt("chunk_count"), pages, rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getString("last_error"), instant(rs, "requested_at"),
                instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "next_attempt_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String column(KnowledgeSourceType type) {
        return type == KnowledgeSourceType.DOCUMENT ? "document_id" : "source_message_id";
    }

    private static MapSqlParameterSource tokenParams(ClaimedSource job) {
        return new MapSqlParameterSource().addValue("id", job.id()).addValue("token", job.claimToken());
    }

    /**
     * @param previousVersion index version of the chunks currently stored, if any
     * @param previousHash    content hash of the chunks currently stored, if any
     */
    public record ClaimedSource(UUID id, KnowledgeSourceType type, UUID sourceId, int attempts, int maxAttempts,
                                UUID claimToken, String previousVersion, String previousHash) {
    }
}

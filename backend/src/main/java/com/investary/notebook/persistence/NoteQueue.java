package com.investary.notebook.persistence;

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

import com.investary.notebook.domain.NoteKind;
import com.investary.notebook.domain.NoteStatus;

/**
 * Generated notes, each row also being its own job (the {@code brief_jobs} pattern): claims use
 * {@code FOR UPDATE SKIP LOCKED} with a fresh claim token, and every result write only applies while the token still
 * matches, so a worker whose lease expired cannot overwrite a newer attempt. A DONE note is never changed.
 */
@Repository
public class NoteQueue {

    private static final String COLUMNS = """
            id, notebook_id, kind, ticker, status, fingerprint, attempts, max_attempts, claim_token, content::text as content,
            limitations::text as limitations, evidence_status, model, error_code, error_summary, created_at, completed_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public NoteQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a PENDING note unless one for the same notebook, kind and ticker is already PENDING or RUNNING.
     *
     * @return the new note's id, or empty when an active note exists
     */
    public Optional<UUID> insert(UUID notebookId, NoteKind kind, String ticker, String fingerprint, int maxAttempts) {
        return jdbc.queryForList("""
                insert into notebook_notes (id, notebook_id, kind, ticker, status, fingerprint, max_attempts)
                values (:id, :notebook, :kind, :ticker, 'PENDING', :fingerprint, :maxAttempts)
                on conflict do nothing returning id""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("notebook", notebookId).addValue("kind", kind.name())
                        .addValue("ticker", ticker).addValue("fingerprint", fingerprint).addValue("maxAttempts", maxAttempts),
                UUID.class).stream().findFirst();
    }

    public Optional<NoteRow> active(UUID notebookId, NoteKind kind, String ticker) {
        return jdbc.query("select " + COLUMNS + " " + """
                 from notebook_notes where notebook_id = :notebook and kind = :kind and coalesce(ticker, '') = coalesce(:ticker, '')
                  and status in ('PENDING', 'RUNNING')""",
                params(notebookId, kind, ticker), NoteQueue::row).stream().findFirst();
    }

    /** The newest DONE note with the same inputs, which makes a new model call unnecessary. */
    public Optional<NoteRow> done(UUID notebookId, NoteKind kind, String ticker, String fingerprint) {
        return jdbc.query("select " + COLUMNS + " " + """
                 from notebook_notes where notebook_id = :notebook and kind = :kind and coalesce(ticker, '') = coalesce(:ticker, '')
                  and status = 'DONE' and fingerprint = :fingerprint order by completed_at desc limit 1""",
                params(notebookId, kind, ticker).addValue("fingerprint", fingerprint), NoteQueue::row).stream().findFirst();
    }

    public Optional<NoteRow> find(UUID notebookId, UUID noteId) {
        return jdbc.query("select " + COLUMNS + " from notebook_notes where id = :id and notebook_id = :notebook",
                new MapSqlParameterSource("id", noteId).addValue("notebook", notebookId), NoteQueue::row).stream().findFirst();
    }

    public List<NoteRow> list(UUID notebookId) {
        return jdbc.query("select " + COLUMNS + " from notebook_notes where notebook_id = :notebook order by created_at desc, id",
                new MapSqlParameterSource("notebook", notebookId), NoteQueue::row);
    }

    /** Notes created since {@code since}, i.e. requests that were not answered by an identical earlier note. */
    public int countCreatedSince(Instant since) {
        Integer count = jdbc.queryForObject("select count(*) from notebook_notes where created_at >= :since",
                new MapSqlParameterSource("since", Timestamp.from(since)), Integer.class);
        return count == null ? 0 : count;
    }

    public List<ClaimedNote> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("""
                update notebook_notes n
                set status = 'RUNNING', claimed_at = now(), claim_token = :token, attempts = n.attempts + 1, updated_at = now()
                from (select id from notebook_notes where status = 'PENDING' and next_attempt_at <= now()
                      order by next_attempt_at, created_at limit :limit for update skip locked) c
                where n.id = c.id
                returning n.id, n.notebook_id, n.kind, n.ticker, n.fingerprint, n.attempts, n.max_attempts, n.claim_token""",
                new MapSqlParameterSource("token", UUID.randomUUID()).addValue("limit", limit),
                (rs, i) -> new ClaimedNote(rs.getObject("id", UUID.class), rs.getObject("notebook_id", UUID.class),
                        NoteKind.valueOf(rs.getString("kind")), rs.getString("ticker"), rs.getString("fingerprint"), rs.getInt("attempts"),
                        rs.getInt("max_attempts"), rs.getObject("claim_token", UUID.class)));
    }

    /** Re-queues notes whose worker stopped without finishing; they keep their attempt count. */
    public int recoverExpired(Duration lease) {
        return jdbc.update("""
                update notebook_notes set status = 'PENDING', claim_token = null, claimed_at = null, next_attempt_at = now(), updated_at = now()
                where status = 'RUNNING' and claimed_at < :cutoff""",
                new MapSqlParameterSource("cutoff", Timestamp.from(Instant.now().minus(lease))));
    }

    /** Locks a claimed note for the completing transaction; false when the claim was lost. */
    public boolean lockClaimed(ClaimedNote note) {
        return !jdbc.queryForList("select id from notebook_notes where id = :id and claim_token = :token and status = 'RUNNING' for update",
                token(note), UUID.class).isEmpty();
    }

    public boolean complete(ClaimedNote note, String contentJson, String limitationsJson, String evidenceStatus, String model) {
        return jdbc.update("""
                update notebook_notes
                set status = 'DONE', content = cast(:content as jsonb), limitations = cast(:limitations as jsonb), evidence_status = :status,
                    model = :model, claim_token = null, claimed_at = null, error_code = null, error_summary = null, completed_at = now(),
                    updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                token(note).addValue("content", contentJson).addValue("limitations", limitationsJson).addValue("status", evidenceStatus)
                        .addValue("model", model)) == 1;
    }

    public boolean retryLater(ClaimedNote note, String errorCode, String errorSummary, Instant next) {
        return jdbc.update("""
                update notebook_notes
                set status = 'PENDING', claim_token = null, claimed_at = null, next_attempt_at = :next, error_code = :code,
                    error_summary = :summary, updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                token(note).addValue("next", Timestamp.from(next)).addValue("code", errorCode).addValue("summary", errorSummary)) == 1;
    }

    public boolean fail(ClaimedNote note, String errorCode, String errorSummary) {
        return jdbc.update("""
                update notebook_notes
                set status = 'FAILED', claim_token = null, claimed_at = null, error_code = :code, error_summary = :summary,
                    completed_at = now(), updated_at = now()
                where id = :id and claim_token = :token and status = 'RUNNING'""",
                token(note).addValue("code", errorCode).addValue("summary", errorSummary)) == 1;
    }

    /** Re-opens a FAILED note with fresh attempts; false when it is not FAILED or another note of its kind is active. */
    public boolean resetFailed(UUID notebookId, UUID noteId) {
        try {
            return jdbc.update("""
                    update notebook_notes
                    set status = 'PENDING', attempts = 0, next_attempt_at = now(), error_code = null, error_summary = null,
                        completed_at = null, updated_at = now()
                    where id = :id and notebook_id = :notebook and status = 'FAILED'""",
                    new MapSqlParameterSource("id", noteId).addValue("notebook", notebookId)) == 1;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    private static MapSqlParameterSource params(UUID notebookId, NoteKind kind, String ticker) {
        return new MapSqlParameterSource("notebook", notebookId).addValue("kind", kind.name()).addValue("ticker", ticker);
    }

    private static MapSqlParameterSource token(ClaimedNote note) {
        return new MapSqlParameterSource("id", note.id()).addValue("token", note.claimToken());
    }

    private static NoteRow row(ResultSet rs, int i) throws SQLException {
        return new NoteRow(rs.getObject("id", UUID.class), rs.getObject("notebook_id", UUID.class), NoteKind.valueOf(rs.getString("kind")),
                rs.getString("ticker"), NoteStatus.valueOf(rs.getString("status")), rs.getString("fingerprint"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getString("content"), rs.getString("limitations"), rs.getString("evidence_status"),
                rs.getString("model"), rs.getString("error_code"), rs.getString("error_summary"), NotebookStore.instant(rs, "created_at"),
                NotebookStore.instant(rs, "completed_at"));
    }

    public record NoteRow(UUID id, UUID notebookId, NoteKind kind, String ticker, NoteStatus status, String fingerprint, int attempts,
                          int maxAttempts, String contentJson, String limitationsJson, String evidenceStatus, String model,
                          String errorCode, String errorSummary, Instant createdAt, Instant completedAt) {
    }

    public record ClaimedNote(UUID id, UUID notebookId, NoteKind kind, String ticker, String fingerprint, int attempt, int maxAttempts,
                              UUID claimToken) {
    }
}

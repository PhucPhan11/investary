package com.investary.automation.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.automation.domain.SyncMode;
import com.investary.automation.domain.SyncRunStatus;
import com.investary.automation.domain.SyncTrigger;

/**
 * Sync run history (one row per manual or scheduled sync).
 */
@Repository
public class GmailSyncRunStore {

    private static final String COLUMNS = """
            id, account_id, trigger_type, mode, status, started_at, finished_at, listed, imported, duplicates, not_imported,
            skipped, rejected, start_history_id, end_history_id, error_code, error_message, warning""";

    private final NamedParameterJdbcTemplate jdbc;

    public GmailSyncRunStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID accountId, SyncTrigger trigger, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into gmail_sync_runs (id, account_id, trigger_type, status, started_at)
                values (:id, :accountId, :trigger, 'RUNNING', :now)""",
                new MapSqlParameterSource("id", id).addValue("accountId", accountId).addValue("trigger", trigger.name())
                        .addValue("now", Timestamp.from(now)));
        return id;
    }

    /**
     * Closes runs left {@code RUNNING} by a stopped process. Called once the caller holds the account lock,
     * so no other run of this account can still be active.
     */
    public int closeInterrupted(UUID accountId, UUID currentRunId, Instant now) {
        return jdbc.update("""
                update gmail_sync_runs
                set status = 'FAILED', finished_at = :now, error_code = 'INTERRUPTED',
                    error_message = 'The sync stopped before it finished (for example, the application was restarted)'
                where account_id = :accountId and status = 'RUNNING' and id <> :current""",
                new MapSqlParameterSource("accountId", accountId).addValue("current", currentRunId)
                        .addValue("now", Timestamp.from(now)));
    }

    public void finish(UUID runId, SyncMode mode, SyncRunStatus status, Counters counters, String startHistoryId,
                       String endHistoryId, String errorCode, String errorMessage, String warning, Instant now) {
        jdbc.update("""
                update gmail_sync_runs
                set mode = :mode, status = :status, finished_at = :now, listed = :listed, imported = :imported,
                    duplicates = :duplicates, not_imported = :notImported, skipped = :skipped, rejected = :rejected,
                    start_history_id = :startHistoryId, end_history_id = :endHistoryId, error_code = :errorCode,
                    error_message = :errorMessage, warning = :warning
                where id = :id""",
                new MapSqlParameterSource("id", runId)
                        .addValue("mode", mode == null ? null : mode.name())
                        .addValue("status", status.name())
                        .addValue("now", Timestamp.from(now))
                        .addValue("listed", counters.listed)
                        .addValue("imported", counters.imported)
                        .addValue("duplicates", counters.duplicates)
                        .addValue("notImported", counters.notImported)
                        .addValue("skipped", counters.skipped)
                        .addValue("rejected", counters.rejected)
                        .addValue("startHistoryId", startHistoryId)
                        .addValue("endHistoryId", endHistoryId)
                        .addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage)
                        .addValue("warning", warning));
    }

    public Optional<RunRow> find(UUID runId) {
        return jdbc.query("select " + COLUMNS + " from gmail_sync_runs where id = :id",
                new MapSqlParameterSource("id", runId), GmailSyncRunStore::row).stream().findFirst();
    }

    public Optional<RunRow> latest(UUID accountId) {
        return jdbc.query("select " + COLUMNS + " from gmail_sync_runs where account_id = :accountId order by started_at desc, id limit 1",
                new MapSqlParameterSource("accountId", accountId), GmailSyncRunStore::row).stream().findFirst();
    }

    public List<RunRow> page(int page, int size) {
        return jdbc.query("select " + COLUMNS + " from gmail_sync_runs order by started_at desc, id limit :size offset :offset",
                new MapSqlParameterSource("size", size).addValue("offset", (long) page * size), GmailSyncRunStore::row);
    }

    public long count() {
        Long count = jdbc.queryForObject("select count(*) from gmail_sync_runs", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    private static RunRow row(ResultSet rs, int i) throws SQLException {
        Counters counters = new Counters();
        counters.listed = rs.getInt("listed");
        counters.imported = rs.getInt("imported");
        counters.duplicates = rs.getInt("duplicates");
        counters.notImported = rs.getInt("not_imported");
        counters.skipped = rs.getInt("skipped");
        counters.rejected = rs.getInt("rejected");
        String mode = rs.getString("mode");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new RunRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                SyncTrigger.valueOf(rs.getString("trigger_type")),
                mode == null ? null : SyncMode.valueOf(mode),
                SyncRunStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                counters,
                rs.getString("start_history_id"),
                rs.getString("end_history_id"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("warning"));
    }

    /** Mutable counters of a running sync. */
    public static final class Counters {
        public int listed;
        public int imported;
        public int duplicates;
        public int notImported;
        public int skipped;
        public int rejected;
    }

    public record RunRow(UUID id, UUID accountId, SyncTrigger trigger, SyncMode mode, SyncRunStatus status, Instant startedAt,
                         Instant finishedAt, Counters counters, String startHistoryId, String endHistoryId, String errorCode,
                         String errorMessage, String warning) {
    }
}

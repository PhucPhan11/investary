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

import com.investary.automation.domain.GmailAccountStatus;

/**
 * Gmail connections, their sync cursor and the per-account sync lock.
 * <p>
 * The lock is a lease ({@code sync_lock_token}, {@code sync_lock_until}): taking it is a single conditional
 * update, so only one sync per account runs at a time, whether manual or scheduled. Every cursor or status
 * write of a running sync is guarded by its token, so a sync whose lease expired cannot overwrite a newer one.
 */
@Repository
public class GmailAccountStore {

    private static final String COLUMNS = """
            id, email_address, status, refresh_token_ciphertext, token_key_id, granted_scopes, connected_at,
            history_id, pending_history_id, initial_sync_completed_at, last_sync_started_at, last_sync_success_at,
            last_sync_error_code, last_sync_error, sync_lock_token, sync_lock_until""";

    private final NamedParameterJdbcTemplate jdbc;

    public GmailAccountStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from gmail_accounts where id = :id",
                new MapSqlParameterSource("id", id), GmailAccountStore::row).stream().findFirst();
    }

    public Optional<AccountRow> findByEmail(String emailAddress) {
        return jdbc.query("select " + COLUMNS + " from gmail_accounts where lower(email_address) = lower(:email)",
                new MapSqlParameterSource("email", emailAddress), GmailAccountStore::row).stream().findFirst();
    }

    /** The account that is connected or needs reconnecting (one at a time in Milestone 1). */
    public Optional<AccountRow> findActive() {
        return jdbc.query("select " + COLUMNS + " from gmail_accounts where status <> 'DISCONNECTED' order by created_at limit 1",
                new MapSqlParameterSource(), GmailAccountStore::row).stream().findFirst();
    }

    public List<UUID> connectedIds() {
        return jdbc.queryForList("select id from gmail_accounts where status = 'CONNECTED' order by created_at",
                new MapSqlParameterSource(), UUID.class);
    }

    public void insertConnected(UUID id, String emailAddress, byte[] ciphertext, String keyId, String scopes, Instant now) {
        jdbc.update("""
                insert into gmail_accounts (id, email_address, status, refresh_token_ciphertext, token_key_id, granted_scopes,
                                            connected_at, created_at, updated_at)
                values (:id, :email, 'CONNECTED', :ciphertext, :keyId, :scopes, :now, :now, :now)""",
                new MapSqlParameterSource("id", id).addValue("email", emailAddress).addValue("ciphertext", ciphertext)
                        .addValue("keyId", keyId).addValue("scopes", scopes).addValue("now", Timestamp.from(now)));
    }

    /** Reconnect: new token, status CONNECTED; the cursor is kept (it may still be valid). */
    public void updateConnected(UUID id, byte[] ciphertext, String keyId, String scopes, Instant now) {
        jdbc.update("""
                update gmail_accounts
                set status = 'CONNECTED', refresh_token_ciphertext = :ciphertext, token_key_id = :keyId, granted_scopes = :scopes,
                    connected_at = :now, disconnected_at = null, last_sync_error_code = null, last_sync_error = null, updated_at = :now
                where id = :id""",
                new MapSqlParameterSource("id", id).addValue("ciphertext", ciphertext).addValue("keyId", keyId)
                        .addValue("scopes", scopes).addValue("now", Timestamp.from(now)));
    }

    /** Google no longer accepts the stored grant: forget it and ask the user to reconnect. */
    public void markReconnectRequired(UUID id, String errorCode, String error, Instant now) {
        jdbc.update("""
                update gmail_accounts
                set status = 'RECONNECT_REQUIRED', refresh_token_ciphertext = null, token_key_id = null,
                    last_sync_error_code = :code, last_sync_error = :error, updated_at = :now
                where id = :id and status <> 'DISCONNECTED'""",
                new MapSqlParameterSource("id", id).addValue("code", errorCode).addValue("error", error)
                        .addValue("now", Timestamp.from(now)));
    }

    /** Disconnect: drop the token and the cursor; the mapping of imported messages is kept. */
    public void disconnect(UUID id, Instant now) {
        jdbc.update("""
                update gmail_accounts
                set status = 'DISCONNECTED', refresh_token_ciphertext = null, token_key_id = null, granted_scopes = null,
                    disconnected_at = :now, history_id = null, pending_history_id = null, initial_sync_completed_at = null,
                    sync_lock_token = null, sync_lock_until = null, updated_at = :now
                where id = :id""",
                new MapSqlParameterSource("id", id).addValue("now", Timestamp.from(now)));
    }

    /**
     * @return true if this caller now holds the account's sync lock
     */
    public boolean tryLock(UUID id, UUID token, Duration lease, Instant now) {
        return jdbc.update("""
                update gmail_accounts
                set sync_lock_token = :token, sync_lock_until = :until, last_sync_started_at = :now, updated_at = :now
                where id = :id and status = 'CONNECTED'
                  and (sync_lock_token is null or sync_lock_until < :now)""",
                lockParams(id, token).addValue("until", Timestamp.from(now.plus(lease))).addValue("now", Timestamp.from(now))) == 1;
    }

    /** @return false if the lock was lost (lease expired and taken over, or account disconnected) */
    public boolean renewLock(UUID id, UUID token, Duration lease, Instant now) {
        return jdbc.update("update gmail_accounts set sync_lock_until = :until where id = :id and sync_lock_token = :token",
                lockParams(id, token).addValue("until", Timestamp.from(now.plus(lease)))) == 1;
    }

    public void releaseLock(UUID id, UUID token) {
        jdbc.update("update gmail_accounts set sync_lock_token = null, sync_lock_until = null where id = :id and sync_lock_token = :token",
                lockParams(id, token));
    }

    /** Records the history id the initial or recovery listing started from. */
    public boolean setPendingHistoryId(UUID id, UUID token, String historyId) {
        return jdbc.update("update gmail_accounts set pending_history_id = :historyId where id = :id and sync_lock_token = :token",
                lockParams(id, token).addValue("historyId", historyId)) == 1;
    }

    /** Advances the incremental cursor; only called once every message up to it is handled. */
    public boolean checkpoint(UUID id, UUID token, String historyId) {
        return jdbc.update("update gmail_accounts set history_id = :historyId where id = :id and sync_lock_token = :token",
                lockParams(id, token).addValue("historyId", historyId)) == 1;
    }

    /** The listing (initial or recovery) is complete: the pending history id becomes the cursor. */
    public boolean completeListing(UUID id, UUID token, Instant now) {
        return jdbc.update("""
                update gmail_accounts
                set history_id = pending_history_id, pending_history_id = null,
                    initial_sync_completed_at = coalesce(initial_sync_completed_at, :now)
                where id = :id and sync_lock_token = :token and pending_history_id is not null""",
                lockParams(id, token).addValue("now", Timestamp.from(now))) == 1;
    }

    public void recordSuccess(UUID id, UUID token, Instant now) {
        jdbc.update("""
                update gmail_accounts set last_sync_success_at = :now, last_sync_error_code = null, last_sync_error = null, updated_at = :now
                where id = :id and sync_lock_token = :token""",
                lockParams(id, token).addValue("now", Timestamp.from(now)));
    }

    public void recordError(UUID id, UUID token, String code, String error, Instant now) {
        jdbc.update("""
                update gmail_accounts set last_sync_error_code = :code, last_sync_error = :error, updated_at = :now
                where id = :id and sync_lock_token = :token""",
                lockParams(id, token).addValue("code", code).addValue("error", error).addValue("now", Timestamp.from(now)));
    }

    private static MapSqlParameterSource lockParams(UUID id, UUID token) {
        return new MapSqlParameterSource("id", id).addValue("token", token);
    }

    private static AccountRow row(ResultSet rs, int i) throws SQLException {
        return new AccountRow(
                rs.getObject("id", UUID.class),
                rs.getString("email_address"),
                GmailAccountStatus.valueOf(rs.getString("status")),
                rs.getBytes("refresh_token_ciphertext"),
                rs.getString("token_key_id"),
                rs.getString("granted_scopes"),
                instant(rs, "connected_at"),
                rs.getString("history_id"),
                rs.getString("pending_history_id"),
                instant(rs, "initial_sync_completed_at"),
                instant(rs, "last_sync_started_at"),
                instant(rs, "last_sync_success_at"),
                rs.getString("last_sync_error_code"),
                rs.getString("last_sync_error"),
                rs.getObject("sync_lock_token", UUID.class),
                instant(rs, "sync_lock_until"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * A Gmail account row. {@code refreshTokenCiphertext} must never leave the application layer.
     */
    public record AccountRow(UUID id, String emailAddress, GmailAccountStatus status, byte[] refreshTokenCiphertext,
                             String tokenKeyId, String grantedScopes, Instant connectedAt, String historyId,
                             String pendingHistoryId, Instant initialSyncCompletedAt, Instant lastSyncStartedAt,
                             Instant lastSyncSuccessAt, String lastSyncErrorCode, String lastSyncError, UUID syncLockToken,
                             Instant syncLockUntil) {

        public boolean syncRunning(Instant now) {
            return syncLockToken != null && syncLockUntil != null && syncLockUntil.isAfter(now);
        }

        @Override
        public String toString() {
            return "AccountRow[id=" + id + ", status=" + status + "]"; // never print the ciphertext
        }
    }
}

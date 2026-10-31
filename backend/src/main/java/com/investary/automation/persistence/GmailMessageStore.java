package com.investary.automation.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.automation.domain.MessageOutcome;

/**
 * Which Gmail messages were handled, and how. {@code (account_id, gmail_message_id)} is the dedup key:
 * a handled message is never fetched or imported again, even if its email was later deleted.
 */
@Repository
public class GmailMessageStore {

    private final NamedParameterJdbcTemplate jdbc;

    public GmailMessageStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID accountId, String gmailMessageId) {
        Boolean exists = jdbc.queryForObject(
                "select exists(select 1 from gmail_messages where account_id = :accountId and gmail_message_id = :messageId)",
                new MapSqlParameterSource("accountId", accountId).addValue("messageId", gmailMessageId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Idempotent: a concurrent or repeated record of the same message is ignored. */
    public void record(UUID accountId, String gmailMessageId, String threadId, String historyId, Instant internalDate,
                       MessageOutcome outcome, UUID sourceRuleId, UUID sourceMessageId, UUID syncRunId, String errorCode,
                       Instant now) {
        jdbc.update("""
                insert into gmail_messages (id, account_id, gmail_message_id, thread_id, history_id, internal_date, status,
                                            source_rule_id, source_message_id, sync_run_id, error_code, processed_at)
                values (:id, :accountId, :messageId, :threadId, :historyId, :internalDate, :status,
                        :ruleId, :sourceMessageId, :runId, :errorCode, :now)
                on conflict (account_id, gmail_message_id) do nothing""",
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("accountId", accountId)
                        .addValue("messageId", gmailMessageId)
                        .addValue("threadId", threadId)
                        .addValue("historyId", historyId)
                        .addValue("internalDate", internalDate == null ? null : Timestamp.from(internalDate))
                        .addValue("status", outcome.name())
                        .addValue("ruleId", sourceRuleId)
                        .addValue("sourceMessageId", sourceMessageId)
                        .addValue("runId", syncRunId)
                        .addValue("errorCode", errorCode)
                        .addValue("now", Timestamp.from(now)));
    }

    public long countImported(UUID accountId) {
        Long count = jdbc.queryForObject(
                "select count(*) from gmail_messages where account_id = :accountId and status = 'IMPORTED'",
                new MapSqlParameterSource("accountId", accountId), Long.class);
        return count == null ? 0 : count;
    }
}

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

import com.investary.automation.domain.NotificationEntity;
import com.investary.automation.domain.NotificationPriority;
import com.investary.automation.domain.NotificationType;

/**
 * {@code notifications}. A notification is inserted at most once per deduplication key, so repeated events,
 * retries and re-runs never create a second one.
 */
@Repository
public class NotificationStore {

    private static final String COLUMNS = """
            id, type, priority, title, message, entity_type, entity_id, portfolio_id, dedup_key, created_at, read_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if the notification was created, false if one with the same key already exists
     */
    public boolean insert(NewRow row, Instant now) {
        return jdbc.update("""
                insert into notifications (id, type, priority, title, message, entity_type, entity_id, portfolio_id, dedup_key, created_at)
                values (:id, :type, :priority, :title, :message, :entityType, :entityId, :portfolioId, :key, :now)
                on conflict (dedup_key) do nothing""",
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("type", row.type().name())
                        .addValue("priority", row.priority().name())
                        .addValue("title", truncate(row.title(), 200))
                        .addValue("message", truncate(row.message(), 1000))
                        .addValue("entityType", row.entityType() == null ? null : row.entityType().name())
                        .addValue("entityId", row.entityId())
                        .addValue("portfolioId", row.portfolioId())
                        .addValue("key", truncate(row.dedupKey(), 200))
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    public Optional<Row> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from notifications where id = :id", new MapSqlParameterSource("id", id),
                NotificationStore::row).stream().findFirst();
    }

    public List<Row> list(boolean unreadOnly, NotificationType type, int page, int size) {
        return jdbc.query("select " + COLUMNS + " from notifications where " + FILTER + " order by created_at desc, id limit :limit offset :offset",
                filter(unreadOnly, type).addValue("limit", size).addValue("offset", (long) page * size), NotificationStore::row);
    }

    public long count(boolean unreadOnly, NotificationType type) {
        Long count = jdbc.queryForObject("select count(*) from notifications where " + FILTER, filter(unreadOnly, type), Long.class);
        return count == null ? 0 : count;
    }

    public boolean setRead(UUID id, Instant readAt) {
        return jdbc.update("update notifications set read_at = :readAt where id = :id",
                new MapSqlParameterSource("id", id).addValue("readAt", readAt == null ? null : Timestamp.from(readAt))) == 1;
    }

    public int markAllRead(Instant now) {
        return jdbc.update("update notifications set read_at = :now where read_at is null",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    private static final String FILTER = """
            (not :unreadOnly or read_at is null) and (cast(:type as varchar) is null or type = :type)""";

    private static MapSqlParameterSource filter(boolean unreadOnly, NotificationType type) {
        return new MapSqlParameterSource("unreadOnly", unreadOnly).addValue("type", type == null ? null : type.name());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        String entity = rs.getString("entity_type");
        Timestamp read = rs.getTimestamp("read_at");
        return new Row(rs.getObject("id", UUID.class), NotificationType.valueOf(rs.getString("type")),
                NotificationPriority.valueOf(rs.getString("priority")), rs.getString("title"), rs.getString("message"),
                entity == null ? null : NotificationEntity.valueOf(entity), rs.getObject("entity_id", UUID.class),
                rs.getObject("portfolio_id", UUID.class), rs.getString("dedup_key"), rs.getTimestamp("created_at").toInstant(),
                read == null ? null : read.toInstant());
    }

    public record NewRow(NotificationType type, NotificationPriority priority, String title, String message, NotificationEntity entityType,
                         UUID entityId, UUID portfolioId, String dedupKey) {
    }

    public record Row(UUID id, NotificationType type, NotificationPriority priority, String title, String message,
                      NotificationEntity entityType, UUID entityId, UUID portfolioId, String dedupKey, Instant createdAt, Instant readAt) {
    }
}

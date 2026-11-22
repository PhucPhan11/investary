package com.investary.notebook.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.notebook.domain.MessageRole;

/**
 * Notebooks, their sources, chat messages and citations. Plain JDBC like the other job-oriented stores; callers
 * open transactions. Citations are copies written once and never updated.
 */
@Repository
public class NotebookStore {

    private final NamedParameterJdbcTemplate jdbc;

    public NotebookStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- notebooks ----

    public void insert(UUID id, String title, String description) {
        jdbc.update("insert into notebooks (id, title, description) values (:id, :title, :description)",
                new MapSqlParameterSource("id", id).addValue("title", title).addValue("description", description));
    }

    /** @return false when the notebook does not exist or its version is not {@code expectedVersion} */
    public boolean update(UUID id, String title, String description, int expectedVersion) {
        return jdbc.update("""
                update notebooks set title = :title, description = :description, version = version + 1, updated_at = now()
                where id = :id and version = :version""",
                new MapSqlParameterSource("id", id).addValue("title", title).addValue("description", description)
                        .addValue("version", expectedVersion)) == 1;
    }

    public void touch(UUID id) {
        jdbc.update("update notebooks set updated_at = now() where id = :id", new MapSqlParameterSource("id", id));
    }

    public boolean delete(UUID id) {
        return jdbc.update("delete from notebooks where id = :id", new MapSqlParameterSource("id", id)) == 1;
    }

    /** Locks the notebook row for the rest of the transaction, so concurrent source changes are applied one at a time. */
    public Optional<NotebookRow> lock(UUID id) {
        return jdbc.query(NOTEBOOK + " where n.id = :id for update of n", new MapSqlParameterSource("id", id), NotebookStore::notebook)
                .stream().findFirst();
    }

    public Optional<NotebookRow> find(UUID id) {
        return jdbc.query(NOTEBOOK + " where n.id = :id", new MapSqlParameterSource("id", id), NotebookStore::notebook).stream().findFirst();
    }

    public List<NotebookRow> list() {
        return jdbc.query(NOTEBOOK + " order by n.updated_at desc, n.id", new MapSqlParameterSource(), NotebookStore::notebook);
    }

    private static final String NOTEBOOK = """
            select n.id, n.title, n.description, n.version, n.created_at, n.updated_at,
                   (select count(*) from notebook_sources s where s.notebook_id = n.id) as sources,
                   (select count(*) from notebook_messages m where m.notebook_id = n.id and m.role = 'USER') as questions,
                   (select count(*) from notebook_notes o where o.notebook_id = n.id and o.status = 'DONE') as notes
            from notebooks n""";

    private static NotebookRow notebook(ResultSet rs, int row) throws SQLException {
        return new NotebookRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getInt("sources"), rs.getInt("questions"), rs.getInt("notes"));
    }

    // ---- sources ----

    public List<SourceRow> sources(UUID notebookId) {
        return jdbc.query("""
                select document_id, source_message_id, added_at from notebook_sources where notebook_id = :id order by added_at, id""",
                new MapSqlParameterSource("id", notebookId),
                (rs, i) -> new SourceRow(rs.getObject("document_id", UUID.class), rs.getObject("source_message_id", UUID.class),
                        instant(rs, "added_at")));
    }

    /** @return how many of the given sources were new to the notebook */
    public int addSources(UUID notebookId, Collection<UUID> documentIds, Collection<UUID> messageIds) {
        int added = 0;
        for (UUID documentId : documentIds) {
            added += jdbc.update("""
                    insert into notebook_sources (id, notebook_id, document_id)
                    select :id, :notebook, d.id from document d where d.id = :source
                    on conflict do nothing""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("notebook", notebookId).addValue("source", documentId));
        }
        for (UUID messageId : messageIds) {
            added += jdbc.update("""
                    insert into notebook_sources (id, notebook_id, source_message_id)
                    select :id, :notebook, m.id from source_messages m where m.id = :source
                    on conflict do nothing""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("notebook", notebookId).addValue("source", messageId));
        }
        return added;
    }

    public boolean removeSource(UUID notebookId, UUID sourceId) {
        return jdbc.update("""
                delete from notebook_sources where notebook_id = :notebook and (document_id = :source or source_message_id = :source)""",
                new MapSqlParameterSource("notebook", notebookId).addValue("source", sourceId)) > 0;
    }

    // ---- messages ----

    public void insertMessage(MessageRow message) {
        jdbc.update("""
                insert into notebook_messages (id, notebook_id, role, content, evidence_status, warnings, model)
                values (:id, :notebook, :role, :content, :status, cast(:warnings as jsonb), :model)""",
                new MapSqlParameterSource("id", message.id()).addValue("notebook", message.notebookId())
                        .addValue("role", message.role().name()).addValue("content", message.content())
                        .addValue("status", message.evidenceStatus()).addValue("warnings", message.warningsJson())
                        .addValue("model", message.model()));
    }

    public List<MessageRow> messages(UUID notebookId) {
        return jdbc.query(MESSAGE + " where notebook_id = :id order by created_at, id", new MapSqlParameterSource("id", notebookId),
                NotebookStore::message);
    }

    /** The most recent {@code limit} messages, oldest first. */
    public List<MessageRow> recentMessages(UUID notebookId, int limit) {
        List<MessageRow> recent = new ArrayList<>(jdbc.query(MESSAGE + " where notebook_id = :id order by created_at desc, id desc limit :limit",
                new MapSqlParameterSource("id", notebookId).addValue("limit", limit), NotebookStore::message));
        java.util.Collections.reverse(recent);
        return recent;
    }

    public int deleteMessages(UUID notebookId) {
        return jdbc.update("delete from notebook_messages where notebook_id = :id", new MapSqlParameterSource("id", notebookId));
    }

    private static final String MESSAGE = "select id, notebook_id, role, content, evidence_status, warnings::text as warnings, model, created_at"
            + " from notebook_messages";

    private static MessageRow message(ResultSet rs, int row) throws SQLException {
        return new MessageRow(rs.getObject("id", UUID.class), rs.getObject("notebook_id", UUID.class), MessageRole.valueOf(rs.getString("role")),
                rs.getString("content"), rs.getString("evidence_status"), rs.getString("warnings"), rs.getString("model"),
                instant(rs, "created_at"));
    }

    // ---- citations ----

    public void insertCitations(UUID messageId, UUID noteId, List<CitationRow> citations) {
        for (CitationRow citation : citations) {
            jdbc.update("""
                    insert into notebook_citations (id, message_id, note_id, label, source_type, document_id, source_message_id, chunk_id,
                                                    chunk_sha256, title, publisher, published_on, date_basis, location, excerpt)
                    values (:id, :message, :note, :label, :type, :document, :email, :chunk, :sha, :title, :publisher, :published,
                            :dateBasis, :location, :excerpt)""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("message", messageId).addValue("note", noteId)
                            .addValue("label", citation.label()).addValue("type", citation.sourceType())
                            .addValue("document", "DOCUMENT".equals(citation.sourceType()) ? citation.sourceId() : null)
                            .addValue("email", "EMAIL".equals(citation.sourceType()) ? citation.sourceId() : null)
                            .addValue("chunk", citation.chunkId()).addValue("sha", citation.chunkSha256())
                            .addValue("title", truncate(citation.title(), 500)).addValue("publisher", truncate(citation.publisher(), 200))
                            .addValue("published", citation.publishedOn() == null ? null : Date.valueOf(citation.publishedOn()))
                            .addValue("dateBasis", truncate(citation.dateBasis(), 16)).addValue("location", truncate(citation.location(), 200))
                            .addValue("excerpt", citation.excerpt()));
        }
    }

    /** @return message id → its citations in label order */
    public Map<UUID, List<CitationRow>> messageCitations(Collection<UUID> messageIds) {
        return citations("message_id", messageIds);
    }

    /** @return note id → its citations in label order */
    public Map<UUID, List<CitationRow>> noteCitations(Collection<UUID> noteIds) {
        return citations("note_id", noteIds);
    }

    private Map<UUID, List<CitationRow>> citations(String owner, Collection<UUID> ids) {
        Map<UUID, List<CitationRow>> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.query("select " + owner + " as owner, label, source_type, coalesce(document_id, source_message_id) as source_id, chunk_id,"
                        + " chunk_sha256, title, publisher, published_on, date_basis, location, excerpt from notebook_citations"
                        + " where " + owner + " in (:ids) order by " + owner + ", length(label), label",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    Date published = rs.getDate("published_on");
                    result.computeIfAbsent(rs.getObject("owner", UUID.class), id -> new ArrayList<>()).add(new CitationRow(
                            rs.getString("label"), rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                            rs.getObject("chunk_id", UUID.class), rs.getString("chunk_sha256"), rs.getString("title"),
                            rs.getString("publisher"), published == null ? null : published.toLocalDate(), rs.getString("date_basis"),
                            rs.getString("location"), rs.getString("excerpt")));
                });
        return result;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record NotebookRow(UUID id, String title, String description, int version, Instant createdAt, Instant updatedAt,
                              int sourceCount, int questionCount, int noteCount) {
    }

    /** Exactly one of {@code documentId} and {@code messageId} is set. */
    public record SourceRow(UUID documentId, UUID messageId, Instant addedAt) {
    }

    public record MessageRow(UUID id, UUID notebookId, MessageRole role, String content, String evidenceStatus, String warningsJson,
                             String model, Instant createdAt) {
    }

    /**
     * @param sourceId document or email id; {@code null} once the source was deleted
     */
    public record CitationRow(String label, String sourceType, UUID sourceId, UUID chunkId, String chunkSha256, String title,
                              String publisher, LocalDate publishedOn, String dateBasis, String location, String excerpt) {
    }
}

package com.investary.research.provenance;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Associations between source messages and documents ({@code message_documents}).
 * A document can be referenced by many messages; inserts are idempotent.
 */
@Repository
public class ProvenanceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProvenanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID messageId, UUID documentId, RelationshipType relationship, UUID linkId, UUID attachmentId) {
        jdbc.update("""
                insert into message_documents (source_message_id, document_id, relationship_type, source_link_id, source_attachment_id)
                values (:messageId, :documentId, :relationship, :linkId, :attachmentId)
                on conflict do nothing""",
                new MapSqlParameterSource()
                        .addValue("messageId", messageId)
                        .addValue("documentId", documentId)
                        .addValue("relationship", relationship.name())
                        .addValue("linkId", linkId)
                        .addValue("attachmentId", attachmentId));
    }

    public List<Association> findByMessage(UUID messageId) {
        return jdbc.query("""
                select document_id, relationship_type, source_link_id, source_attachment_id, created_at
                from message_documents where source_message_id = :messageId order by created_at""",
                Map.of("messageId", messageId),
                (rs, i) -> new Association(
                        rs.getObject("document_id", UUID.class),
                        RelationshipType.valueOf(rs.getString("relationship_type")),
                        rs.getObject("source_link_id", UUID.class),
                        rs.getObject("source_attachment_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public List<UUID> findMessageIdsByDocument(UUID documentId) {
        return jdbc.queryForList(
                "select distinct source_message_id from message_documents where document_id = :documentId",
                Map.of("documentId", documentId), UUID.class);
    }

    /** Number of distinct documents linked to each message. */
    public Map<UUID, Integer> countDocuments(Collection<UUID> messageIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (messageIds.isEmpty()) {
            return counts;
        }
        jdbc.query("""
                select source_message_id, count(distinct document_id) as documents
                from message_documents where source_message_id in (:ids) group by source_message_id""",
                Map.of("ids", messageIds),
                rs -> {
                    counts.put(rs.getObject("source_message_id", UUID.class), rs.getInt("documents"));
                });
        return counts;
    }

    public enum RelationshipType {
        ATTACHMENT,
        LINKED_REPORT
    }

    public record Association(UUID documentId, RelationshipType relationshipType, UUID sourceLinkId,
                              UUID sourceAttachmentId, Instant createdAt) {
    }
}

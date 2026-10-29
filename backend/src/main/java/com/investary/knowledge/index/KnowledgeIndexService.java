package com.investary.knowledge.index;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.investary.document.application.DocumentService;
import com.investary.email.application.EmailQueryService;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.embedding.EmbeddingService;

/**
 * Use cases for the knowledge base index: queue sources, reindex, and report status.
 */
@Service
public class KnowledgeIndexService {

    private final KnowledgeIndexQueue queue;
    private final DocumentService documents;
    private final EmailQueryService emails;
    private final EmbeddingService embeddings;
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<KnowledgeIndexWorker> worker;
    private final IndexVersion version;
    private final KnowledgeProperties properties;
    private final NamedParameterJdbcTemplate jdbc;

    public KnowledgeIndexService(KnowledgeIndexQueue queue, DocumentService documents, EmailQueryService emails,
                                 EmbeddingService embeddings, ObjectProvider<ChatModel> chatModels,
                                 ObjectProvider<KnowledgeIndexWorker> worker, IndexVersion version,
                                 KnowledgeProperties properties, NamedParameterJdbcTemplate jdbc) {
        this.queue = queue;
        this.documents = documents;
        this.emails = emails;
        this.embeddings = embeddings;
        this.chatModels = chatModels;
        this.worker = worker;
        this.version = version;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /**
     * Queues a source for indexing, or for retry/reindex if it was indexed before.
     *
     * @throws com.investary.document.application.DocumentNotFoundException       for an unknown document
     * @throws com.investary.email.application.EmailExceptions.EmailNotFoundException for an unknown email
     */
    public KnowledgeJob request(KnowledgeSourceType type, UUID sourceId) {
        requireSource(type, sourceId);
        UUID id = queue.request(type, sourceId, properties.worker().maxAttempts());
        return queue.find(id).orElseThrow(() -> new KnowledgeJobNotFoundException(id));
    }

    public Optional<KnowledgeJob> forSource(KnowledgeSourceType type, UUID sourceId) {
        requireSource(type, sourceId);
        return queue.findBySource(type, sourceId);
    }

    public KnowledgeJob job(UUID id) {
        return queue.find(id).orElseThrow(() -> new KnowledgeJobNotFoundException(id));
    }

    public int reindex(ReindexScope scope) {
        if (scope == ReindexScope.MISSING) {
            return queue.trackMissing(properties.worker().maxAttempts());
        }
        return queue.requeue(scope, version.current(), properties.worker().maxAttempts());
    }

    public boolean isCurrent(KnowledgeJob job) {
        return version.current().equals(job.indexVersion());
    }

    public KnowledgeStatus status() {
        Map<KnowledgeSourceStatus, Long> counts = new EnumMap<>(KnowledgeSourceStatus.class);
        for (KnowledgeSourceStatus status : KnowledgeSourceStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("select status, count(*) as n from knowledge_sources group by status", rs -> {
            counts.put(KnowledgeSourceStatus.valueOf(rs.getString("status")), rs.getLong("n"));
        });
        var params = new MapSqlParameterSource("version", version.current());
        long stale = count("""
                select count(*) from knowledge_sources
                where status in ('INDEXED', 'SKIPPED') and index_version is distinct from :version""", params);
        long untracked = count("""
                select (select count(*) from document d where not exists (select 1 from knowledge_sources k where k.document_id = d.id))
                     + (select count(*) from source_messages m where not exists (select 1 from knowledge_sources k where k.source_message_id = m.id))""",
                params);
        long chunks = count("""
                select count(*) from knowledge_chunks c join knowledge_sources s on s.id = c.knowledge_source_id
                where s.status = 'INDEXED' and c.index_version = :version""", params);
        long searchableSources = count("""
                select count(*) from knowledge_sources where status = 'INDEXED' and index_version = :version""", params);
        return new KnowledgeStatus(
                new KnowledgeStatus.Model(embeddings.isAvailable(), properties.embedding().provider(),
                        properties.embedding().model(), properties.embedding().dimensions()),
                new KnowledgeStatus.Model(chatModels.getIfAvailable() != null, null, properties.assistant().modelLabel(), null),
                version.current(), properties.autoIndex(), worker.getIfAvailable() != null,
                counts, stale, untracked, searchableSources, chunks);
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private void requireSource(KnowledgeSourceType type, UUID sourceId) {
        switch (type) {
            case DOCUMENT -> documents.get(sourceId);
            case EMAIL -> emails.require(sourceId);
        }
    }
}

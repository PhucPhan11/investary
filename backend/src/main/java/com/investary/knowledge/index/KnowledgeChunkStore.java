package com.investary.knowledge.index;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.knowledge.chunking.Chunk;
import com.investary.knowledge.embedding.PgVectors;
import com.investary.knowledge.extraction.SourceText;
import com.investary.knowledge.index.KnowledgeIndexQueue.ClaimedSource;
import com.investary.research.link.Keywords;

/**
 * Writes chunks and vectors. A source's chunks are replaced and the source marked INDEXED in a single
 * transaction that only commits while the worker still holds the claim, so searches see either the old
 * complete index or the new complete index, never a partial one.
 */
@Repository
public class KnowledgeChunkStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public KnowledgeChunkStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * Vectors already stored for identical embedding input with the same model, keyed by content hash.
     * Lets reindexing and duplicate text across sources avoid new embedding calls.
     */
    public Map<String, float[]> existingEmbeddings(Collection<String> hashes, String model, int dimensions) {
        Map<String, float[]> found = new HashMap<>();
        if (hashes.isEmpty()) {
            return found;
        }
        jdbc.query("""
                select distinct on (content_sha256) content_sha256, embedding::text as embedding
                from knowledge_chunks
                where content_sha256 in (:hashes) and embedding_model = :model and embedding_dimensions = :dimensions""",
                new MapSqlParameterSource().addValue("hashes", hashes).addValue("model", model).addValue("dimensions", dimensions),
                rs -> {
                    found.put(rs.getString("content_sha256"), PgVectors.parse(rs.getString("embedding")));
                });
        return found;
    }

    /**
     * @return false when the claim was lost (lease expired or source deleted); nothing is written then
     */
    public boolean replace(ClaimedSource job, SourceText source, List<PreparedChunk> chunks, String version,
                           String model, int dimensions, String contentHash) {
        Boolean written = transactions.execute(status -> {
            int claimed = jdbc.update("""
                    update knowledge_sources
                    set status = 'INDEXED', status_reason = null, index_version = :version, content_sha256 = :hash,
                        chunk_count = :chunks, page_count = :pages, claim_token = null, last_error = null,
                        completed_at = now(), updated_at = now()
                    where id = :id and claim_token = :token and status = 'PROCESSING'""",
                    new MapSqlParameterSource()
                            .addValue("id", job.id()).addValue("token", job.claimToken())
                            .addValue("version", version).addValue("hash", contentHash)
                            .addValue("chunks", chunks.size()).addValue("pages", source.text().pageCount()));
            if (claimed != 1) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("delete from knowledge_chunks where knowledge_source_id = :id", new MapSqlParameterSource("id", job.id()));
            List<SqlParameterSource> rows = new ArrayList<>(chunks.size());
            for (PreparedChunk prepared : chunks) {
                Chunk chunk = prepared.chunk();
                rows.add(new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("sourceId", job.id())
                        .addValue("index", chunk.index())
                        .addValue("content", chunk.content())
                        .addValue("hash", prepared.contentHash())
                        .addValue("pageStart", chunk.pageStart())
                        .addValue("pageEnd", chunk.pageEnd())
                        .addValue("section", truncate(chunk.section(), 500))
                        .addValue("title", truncate(source.title(), 1000))
                        .addValue("brokerage", truncate(source.brokerage(), 200))
                        .addValue("publishedOn", source.publishedOn() == null ? null : Date.valueOf(source.publishedOn()))
                        .addValue("dateBasis", source.dateBasis() == null ? null : source.dateBasis().name())
                        .addValue("tickers", String.join(",", chunk.tickers()))
                        .addValue("sourceTicker", source.sourceTicker())
                        .addValue("language", chunk.language())
                        .addValue("embedding", PgVectors.format(prepared.embedding()))
                        .addValue("model", model)
                        .addValue("dimensions", dimensions)
                        .addValue("version", version)
                        .addValue("searchText", searchText(source.title(), chunk)));
            }
            jdbc.batchUpdate("""
                    insert into knowledge_chunks (id, knowledge_source_id, chunk_index, content, content_sha256, page_start,
                        page_end, section, source_title, brokerage, published_on, date_basis, tickers, source_ticker, language,
                        embedding, embedding_model, embedding_dimensions, index_version, search_vector)
                    values (:id, :sourceId, :index, :content, :hash, :pageStart, :pageEnd, :section, :title, :brokerage,
                        :publishedOn, :dateBasis, coalesce(string_to_array(nullif(:tickers, ''), ','), '{}'), :sourceTicker, :language,
                        cast(:embedding as vector), :model, :dimensions, :version, to_tsvector('simple', :searchText))""",
                    rows.toArray(SqlParameterSource[]::new));
            return true;
        });
        return Boolean.TRUE.equals(written);
    }

    /**
     * Accent-folded text for lexical search, so "Long Thanh" matches "Long Thành".
     */
    static String searchText(String title, Chunk chunk) {
        StringBuilder text = new StringBuilder();
        if (title != null) {
            text.append(title).append(' ');
        }
        if (chunk.section() != null) {
            text.append(chunk.section()).append(' ');
        }
        text.append(chunk.content());
        return Keywords.normalize(text.toString());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * @param contentHash SHA-256 of the exact text that was embedded
     */
    public record PreparedChunk(Chunk chunk, String contentHash, float[] embedding) {
    }
}

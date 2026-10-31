package com.investary.knowledge.search;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeSourceType;

/**
 * Read-only lookup of searchable chunks. Used to tell whether evidence cited earlier still exists
 * unchanged: a chunk that was deleted, reindexed with other text, or belongs to an outdated index
 * version is missing from the result.
 */
@Service
public class ChunkLookupService {

    private final NamedParameterJdbcTemplate jdbc;
    private final IndexVersion version;

    public ChunkLookupService(NamedParameterJdbcTemplate jdbc, IndexVersion version) {
        this.jdbc = jdbc;
        this.version = version;
    }

    /**
     * @return chunk id → SHA-256 of its embedded text, for the given ids that are currently searchable
     */
    public Map<UUID, String> currentHashes(Collection<UUID> chunkIds) {
        Map<UUID, String> result = new HashMap<>();
        if (chunkIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select c.id, c.content_sha256 from knowledge_chunks c
                join knowledge_sources s on s.id = c.knowledge_source_id
                where c.id in (:ids) and s.status = 'INDEXED' and c.index_version = :version""",
                new MapSqlParameterSource().addValue("ids", chunkIds).addValue("version", version.current()),
                rs -> {
                    result.put(rs.getObject(1, UUID.class), rs.getString(2));
                });
        return result;
    }

    /**
     * Tickers recognised in the searchable chunks of one source (current index version only), upper case.
     */
    public Set<String> sourceTickers(KnowledgeSourceType type, UUID sourceId) {
        Set<String> tickers = new TreeSet<>();
        jdbc.query("""
                select distinct t.ticker from knowledge_chunks c
                join knowledge_sources s on s.id = c.knowledge_source_id
                cross join lateral (
                    select unnest(c.tickers) as ticker
                    union all
                    select c.source_ticker) t
                where s.status = 'INDEXED' and c.index_version = :version and t.ticker is not null
                  and ((:type = 'DOCUMENT' and s.document_id = :id) or (:type = 'EMAIL' and s.source_message_id = :id))""",
                new MapSqlParameterSource().addValue("type", type.name()).addValue("id", sourceId).addValue("version", version.current()),
                rs -> {
                    tickers.add(rs.getString(1).strip().toUpperCase(Locale.ROOT));
                });
        return tickers;
    }
}

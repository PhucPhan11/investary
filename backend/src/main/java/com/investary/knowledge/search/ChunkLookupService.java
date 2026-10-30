package com.investary.knowledge.search;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.investary.knowledge.IndexVersion;

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
}

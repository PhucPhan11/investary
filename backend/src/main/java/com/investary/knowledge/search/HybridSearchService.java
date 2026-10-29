package com.investary.knowledge.search;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.embedding.EmbeddingService;
import com.investary.knowledge.embedding.PgVectors;
import com.investary.knowledge.search.SearchHit.MatchType;
import com.investary.research.link.BrokerageRegistry;
import com.investary.research.link.Keywords;

/**
 * Hybrid retrieval in PostgreSQL: a pgvector similarity leg plus a full-text leg over accent-folded
 * text, combined with Reciprocal Rank Fusion and a bonus for chunks naming a ticker from the question.
 * <ul>
 *   <li>Only INDEXED sources with the active index version are searched.</li>
 *   <li>When the question names tickers, chunks that name only other tickers are dropped (FPT vs FRT).</li>
 *   <li>A chunk is returned only if it is similar enough, names a ticker from the question, or contains
 *       at least half of the question's terms, so results are never padded to fill the limit.</li>
 *   <li>At most {@code maxChunksPerSource} chunks per source, so several sources can contribute.</li>
 *   <li>When the question names brokerages, each is searched separately and brokerages without
 *       evidence are reported as coverage gaps.</li>
 * </ul>
 * Without an embedding model the lexical leg still works and the result says so.
 */
@Service
public class HybridSearchService {

    private static final int RRF_K = 60;
    private static final int CANDIDATE_FACTOR = 4;
    private static final double MIN_TERM_COVERAGE = 0.5;

    private final NamedParameterJdbcTemplate jdbc;
    private final EmbeddingService embeddings;
    private final IndexVersion version;
    private final BrokerageRegistry brokerages;
    private final KnowledgeProperties.Search settings;

    public HybridSearchService(NamedParameterJdbcTemplate jdbc, EmbeddingService embeddings, IndexVersion version,
                               BrokerageRegistry brokerages, KnowledgeProperties properties) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.version = version;
        this.brokerages = brokerages;
        this.settings = properties.search();
    }

    public SearchResult search(String query, SearchFilters filters, Integer maxResults) {
        int limit = Math.min(maxResults == null ? settings.defaultResults() : maxResults, settings.maxResults());
        QueryAnalysis analysis = QueryAnalysis.of(query, brokerages.names());
        boolean vector = embeddings.isAvailable();
        String queryVector = vector ? embedQuery(query) : null;
        SearchResult.Mode mode = vector ? SearchResult.Mode.HYBRID : SearchResult.Mode.LEXICAL_ONLY;

        List<String> requested = filters.brokerage() != null ? List.of(filters.brokerage()) : analysis.brokerages();
        if (requested.isEmpty()) {
            return new SearchResult(retrieve(analysis, filters, queryVector, limit), mode, analysis, List.of());
        }

        // Search each requested brokerage on its own so one prolific source cannot crowd out the others.
        int perBrokerage = Math.max(2, (int) Math.ceil((double) limit / requested.size()));
        List<SearchHit> merged = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (String brokerage : requested) {
            List<SearchHit> hits = retrieve(analysis, filters.withBrokerage(brokerage), queryVector, perBrokerage);
            if (hits.isEmpty()) {
                gaps.add(brokerage);
            }
            merged.addAll(hits);
        }
        if (merged.isEmpty() && filters.brokerage() == null) {
            // None of the named brokerages has evidence: fall back to all sources, and say so.
            merged = retrieve(analysis, filters, queryVector, limit);
        }
        merged.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return new SearchResult(merged.stream().limit(Math.max(limit, perBrokerage * requested.size())).toList(), mode, analysis, gaps);
    }

    private String embedQuery(String query) {
        try {
            return PgVectors.format(embeddings.embedQuery(query));
        } catch (RuntimeException e) {
            throw new AiProviderException("The embedding model could not process the question (" + e.getClass().getSimpleName()
                    + "). Check that the provider is running and the model is available.");
        }
    }

    private List<SearchHit> retrieve(QueryAnalysis analysis, SearchFilters filters, String queryVector, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("version", version.current())
                .addValue("candidates", limit * CANDIDATE_FACTOR)
                .addValue("queryVector", queryVector);
        String where = whereClause(filters, params);
        String similarity = queryVector == null ? "null::float8" : "1 - (c.embedding <=> cast(:queryVector as vector))";

        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        if (queryVector != null) {
            List<SearchHit> vectorHits = jdbc.query(SELECT.formatted(similarity) + where
                    + " order by c.embedding <=> cast(:queryVector as vector) limit :candidates", params, HybridSearchService::map);
            for (int i = 0; i < vectorHits.size(); i++) {
                candidates.computeIfAbsent(vectorHits.get(i).chunkId(), id -> new Candidate()).vector(vectorHits.get(i), i + 1);
            }
        }
        String lexicalQuery = analysis.lexicalQuery();
        if (!lexicalQuery.isBlank()) {
            params.addValue("lexicalQuery", lexicalQuery);
            List<SearchHit> lexicalHits = jdbc.query(SELECT.formatted(similarity) + where
                    + " and c.search_vector @@ websearch_to_tsquery('simple', :lexicalQuery)"
                    + " order by ts_rank_cd(c.search_vector, websearch_to_tsquery('simple', :lexicalQuery)) desc limit :candidates",
                    params, HybridSearchService::map);
            for (int i = 0; i < lexicalHits.size(); i++) {
                candidates.computeIfAbsent(lexicalHits.get(i).chunkId(), id -> new Candidate()).lexical(lexicalHits.get(i), i + 1);
            }
        }

        List<SearchHit> kept = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            SearchHit hit = candidate.hit;
            boolean tickerHit = analysis.tickers().stream().anyMatch(t -> hit.tickers().contains(t) || t.equals(hit.sourceTicker()));
            if (!analysis.tickers().isEmpty() && !tickerHit && (!hit.tickers().isEmpty() || hit.sourceTicker() != null)) {
                continue; // about other tickers only, e.g. an FRT report for a question about FPT
            }
            boolean similar = hit.similarity() != null && hit.similarity() >= settings.minSimilarity();
            boolean lexicalMatch = candidate.lexicalRank > 0
                    && analysis.coverage(Keywords.normalize(hit.title() + " " + nullToEmpty(hit.section()) + " " + hit.content())) >= MIN_TERM_COVERAGE;
            if (!similar && !tickerHit && !lexicalMatch) {
                continue;
            }
            double score = 0;
            EnumSet<MatchType> matched = EnumSet.noneOf(MatchType.class);
            if (candidate.vectorRank > 0 && similar) {
                score += 1.0 / (RRF_K + candidate.vectorRank);
                matched.add(MatchType.VECTOR);
            }
            if (candidate.lexicalRank > 0) {
                score += 1.0 / (RRF_K + candidate.lexicalRank);
                matched.add(MatchType.LEXICAL);
            }
            if (tickerHit) {
                score += 1.0 / (RRF_K + 1);
                matched.add(MatchType.TICKER);
            }
            kept.add(withScore(hit, score, matched));
        }
        kept.sort(Comparator.comparingDouble(SearchHit::score).reversed());

        Map<UUID, Integer> perSource = new HashMap<>();
        List<SearchHit> result = new ArrayList<>();
        for (SearchHit hit : kept) {
            if (result.size() >= limit) {
                break;
            }
            int used = perSource.merge(hit.knowledgeSourceId(), 1, Integer::sum);
            if (used <= settings.maxChunksPerSource()) {
                result.add(hit);
            }
        }
        return result;
    }

    private static String whereClause(SearchFilters filters, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" where s.status = 'INDEXED' and c.index_version = :version");
        if (filters.ticker() != null) {
            where.append(" and (:ticker = any(c.tickers) or c.source_ticker = :ticker)");
            params.addValue("ticker", filters.ticker());
        }
        if (filters.brokerage() != null) {
            where.append(" and lower(c.brokerage) = lower(:brokerage)");
            params.addValue("brokerage", filters.brokerage());
        }
        if (filters.sourceType() != null) {
            where.append(" and s.source_type = :sourceType");
            params.addValue("sourceType", filters.sourceType().name());
        }
        if (filters.from() != null) {
            where.append(" and c.published_on >= :from");
            params.addValue("from", Date.valueOf(filters.from()));
        }
        if (filters.to() != null) {
            where.append(" and c.published_on <= :to");
            params.addValue("to", Date.valueOf(filters.to()));
        }
        return where.toString();
    }

    /** Joins the email row only for the sender shown in citations. */
    private static final String SELECT = """
            select c.id, c.knowledge_source_id, s.source_type, coalesce(s.document_id, s.source_message_id) as source_id,
                   c.source_title, c.brokerage, c.published_on, c.date_basis, c.page_start, c.page_end, c.section,
                   c.chunk_index, c.content, c.tickers, c.source_ticker, m.sender_name, m.sender_address, m.sent_at,
                   %s as similarity
            from knowledge_chunks c
            join knowledge_sources s on s.id = c.knowledge_source_id
            left join source_messages m on m.id = s.source_message_id""";

    private static SearchHit map(ResultSet rs, int row) throws SQLException {
        Date published = rs.getDate("published_on");
        Timestamp sent = rs.getTimestamp("sent_at");
        Array tickers = rs.getArray("tickers");
        double similarity = rs.getDouble("similarity");
        Double similarityValue = rs.wasNull() ? null : similarity;
        return new SearchHit(rs.getObject("id", UUID.class), rs.getObject("knowledge_source_id", UUID.class),
                KnowledgeSourceType.valueOf(rs.getString("source_type")), rs.getObject("source_id", UUID.class),
                rs.getString("source_title"), rs.getString("brokerage"), published == null ? null : published.toLocalDate(),
                rs.getString("date_basis"), rs.getObject("page_start", Integer.class), rs.getObject("page_end", Integer.class),
                rs.getString("section"), rs.getInt("chunk_index"), rs.getString("content"),
                tickers == null ? List.of() : Arrays.asList((String[]) tickers.getArray()), rs.getString("source_ticker"),
                rs.getString("sender_name"), rs.getString("sender_address"), sent == null ? null : sent.toInstant(),
                similarityValue, 0, EnumSet.noneOf(MatchType.class));
    }

    private static SearchHit withScore(SearchHit hit, double score, EnumSet<MatchType> matched) {
        return new SearchHit(hit.chunkId(), hit.knowledgeSourceId(), hit.sourceType(), hit.sourceId(), hit.title(),
                hit.brokerage(), hit.publishedOn(), hit.dateBasis(), hit.pageStart(), hit.pageEnd(), hit.section(),
                hit.chunkIndex(), hit.content(), hit.tickers(), hit.sourceTicker(), hit.senderName(), hit.senderAddress(), hit.sentAt(),
                hit.similarity(), score, matched);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Candidate {
        private SearchHit hit;
        private int vectorRank;
        private int lexicalRank;

        void vector(SearchHit value, int rank) {
            hit = value;
            vectorRank = rank;
        }

        void lexical(SearchHit value, int rank) {
            if (hit == null) {
                hit = value;
            }
            lexicalRank = rank;
        }
    }
}

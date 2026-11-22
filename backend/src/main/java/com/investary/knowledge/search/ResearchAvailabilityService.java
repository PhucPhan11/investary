package com.investary.knowledge.search;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeSourceType;

/**
 * Read-only answers to "what became searchable in this window" and "what arrived but is not searchable
 * yet", for reports such as the daily brief. Four dates are kept apart: the publication date (or email sent
 * date, see {@code dateBasis}), the email sent date, the import/upload time and the time the source
 * <em>first</em> became searchable ({@code first_indexed_at}, kept across reindexing).
 */
@Service
public class ResearchAvailabilityService {

    private final NamedParameterJdbcTemplate jdbc;
    private final IndexVersion version;

    public ResearchAvailabilityService(NamedParameterJdbcTemplate jdbc, IndexVersion version) {
        this.jdbc = jdbc;
        this.version = version;
    }

    /**
     * Sources that first became searchable in [from, to) and not after {@code cutoff}, oldest first. Only
     * sources that are searchable now (INDEXED, current index version, at least one chunk) are returned.
     */
    public List<AvailableSource> available(Instant from, Instant to, Instant cutoff) {
        return jdbc.query("""
                select s.id, s.source_type, coalesce(s.document_id, s.source_message_id) as source_id, s.first_indexed_at,
                       coalesce(d.uploaded_at, m.imported_at) as imported_at, m.sent_at, s.content_sha256,
                       coalesce(agg.title, d.original_filename, m.subject) as title,
                       coalesce(agg.brokerage, d.brokerage_company, m.brokerage) as brokerage,
                       agg.published_on, agg.date_basis, coalesce(agg.source_ticker, d.ticker) as source_ticker, d.report_type,
                       agg.tickers
                from knowledge_sources s
                left join document d on d.id = s.document_id
                left join source_messages m on m.id = s.source_message_id
                cross join lateral (
                    select max(c.source_title) as title, max(c.brokerage) as brokerage, max(c.published_on) as published_on,
                           max(c.date_basis) as date_basis, max(c.source_ticker) as source_ticker,
                           coalesce((select array_agg(distinct t order by t) from knowledge_chunks c2, unnest(c2.tickers) t
                                     where c2.knowledge_source_id = s.id and c2.index_version = :version), '{}') as tickers,
                           count(*) as chunks
                    from knowledge_chunks c
                    where c.knowledge_source_id = s.id and c.index_version = :version) agg
                where s.status = 'INDEXED' and s.index_version = :version and agg.chunks > 0
                  and s.first_indexed_at >= :from and s.first_indexed_at < :to and s.first_indexed_at <= :cutoff
                order by s.first_indexed_at, s.id""",
                window(from, to).addValue("cutoff", Timestamp.from(cutoff)),
                ResearchAvailabilityService::available);
    }

    /**
     * Emails and library/attached/downloaded documents imported in [from, to) that are not searchable:
     * never tracked, waiting, being processed, failed, skipped (no text) or indexed with an older version.
     */
    public List<PendingSource> pending(Instant from, Instant to) {
        return jdbc.query("""
                select 'DOCUMENT' as source_type, d.id as source_id, d.original_filename as title, d.brokerage_company as brokerage,
                       d.uploaded_at as imported_at, null::timestamptz as sent_at, s.status, s.index_version
                from document d
                left join knowledge_sources s on s.document_id = d.id
                where d.uploaded_at >= :from and d.uploaded_at < :to
                  and (s.id is null or s.status <> 'INDEXED' or s.index_version is distinct from :version)
                union all
                select 'EMAIL', m.id, m.subject, m.brokerage, m.imported_at, m.sent_at, s.status, s.index_version
                from source_messages m
                left join knowledge_sources s on s.source_message_id = m.id
                where m.imported_at >= :from and m.imported_at < :to
                  and (s.id is null or s.status <> 'INDEXED' or s.index_version is distinct from :version)
                order by imported_at, source_id""",
                window(from, to),
                (rs, i) -> new PendingSource(KnowledgeSourceType.valueOf(rs.getString("source_type")),
                        rs.getObject("source_id", UUID.class), rs.getString("title"), rs.getString("brokerage"),
                        instant(rs, "imported_at"), instant(rs, "sent_at"), state(rs.getString("status"), rs.getString("index_version"))));
    }

    /**
     * Searchable chunks of one source: chunks naming one of {@code preferredTickers} first, then in document
     * order. Values are copied from stored records, ready to be cited.
     */
    public List<SearchHit> chunks(UUID knowledgeSourceId, Collection<String> preferredTickers, int limit) {
        // Array literal for cast(:tickers as text[]); only ticker-like values are kept, so no quoting is needed.
        String tickers = "{" + String.join(",", preferredTickers.stream().map(t -> t.strip().toUpperCase(Locale.ROOT))
                .filter(t -> t.matches("[A-Z0-9]{1,15}")).distinct().toList()) + "}";
        return jdbc.query(HybridSearchService.SELECT.formatted("null::float8") + """
                 where s.id = :id and s.status = 'INDEXED' and c.index_version = :version
                order by case when c.tickers && cast(:tickers as text[]) or c.source_ticker = any(cast(:tickers as text[])) then 0 else 1 end,
                         c.chunk_index
                limit :limit""",
                new MapSqlParameterSource("id", knowledgeSourceId).addValue("version", version.current())
                        .addValue("tickers", tickers).addValue("limit", Math.max(1, limit)),
                HybridSearchService::map);
    }

    /**
     * The given library documents and emails with their index state and the metadata stored at index time (falling back
     * to the document or email record). Ids that do not exist are left out. Ordered by source date, newest first.
     */
    public List<SourceState> describe(Collection<UUID> documentIds, Collection<UUID> messageIds) {
        if (documentIds.isEmpty() && messageIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                with picked as (
                    select 'DOCUMENT' as source_type, d.id as source_id, d.original_filename as fallback_title,
                           d.brokerage_company as fallback_brokerage, d.ticker as fallback_ticker, d.uploaded_at as imported_at,
                           null::timestamptz as sent_at
                    from document d where d.id = any(cast(:documents as uuid[]))
                    union all
                    select 'EMAIL', m.id, m.subject, m.brokerage, null, m.imported_at, m.sent_at
                    from source_messages m where m.id = any(cast(:messages as uuid[])))
                select p.source_type, p.source_id, p.imported_at, p.sent_at, s.id as knowledge_source_id, s.status, s.index_version,
                       s.content_sha256, coalesce(agg.title, p.fallback_title) as title,
                       coalesce(agg.brokerage, p.fallback_brokerage) as brokerage, agg.published_on, agg.date_basis,
                       coalesce(agg.source_ticker, p.fallback_ticker) as source_ticker, agg.tickers, coalesce(agg.chunks, 0) as chunks
                from picked p
                left join knowledge_sources s
                       on (p.source_type = 'DOCUMENT' and s.document_id = p.source_id)
                       or (p.source_type = 'EMAIL' and s.source_message_id = p.source_id)
                left join lateral (
                    select max(c.source_title) as title, max(c.brokerage) as brokerage, max(c.published_on) as published_on,
                           max(c.date_basis) as date_basis, max(c.source_ticker) as source_ticker,
                           coalesce((select array_agg(distinct t order by t) from knowledge_chunks c2, unnest(c2.tickers) t
                                     where c2.knowledge_source_id = s.id and c2.index_version = :version), '{}') as tickers,
                           count(*) as chunks
                    from knowledge_chunks c
                    where c.knowledge_source_id = s.id and c.index_version = :version) agg on true
                order by coalesce(agg.published_on, cast(coalesce(p.sent_at, p.imported_at) as date)) desc nulls last, p.source_id""",
                new MapSqlParameterSource().addValue("documents", HybridSearchService.uuidArray(documentIds))
                        .addValue("messages", HybridSearchService.uuidArray(messageIds)).addValue("version", version.current()),
                (rs, i) -> {
                    Date published = rs.getDate("published_on");
                    Array tickers = rs.getArray("tickers");
                    String state = state(rs.getString("status"), rs.getString("index_version"));
                    if (state.equals("INDEXED") && rs.getInt("chunks") == 0) {
                        state = "SKIPPED";
                    }
                    return new SourceState(KnowledgeSourceType.valueOf(rs.getString("source_type")), rs.getObject("source_id", UUID.class),
                            rs.getObject("knowledge_source_id", UUID.class), rs.getString("title"), rs.getString("brokerage"),
                            published == null ? null : published.toLocalDate(), rs.getString("date_basis"), instant(rs, "sent_at"),
                            instant(rs, "imported_at"), rs.getString("source_ticker"),
                            tickers == null ? List.of() : List.copyOf(Arrays.asList((String[]) tickers.getArray())), state,
                            rs.getString("content_sha256"));
                });
    }

    /**
     * Searchable documents and emails with at least one chunk matching the filters, newest first. Used to turn a filter
     * into a fixed list of sources once, so the list does not change as new research arrives.
     */
    public List<SourceRef> matching(SearchFilters filters, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("version", version.current()).addValue("limit", Math.max(1, limit));
        String where = HybridSearchService.whereClause(filters, params);
        return jdbc.query("select s.source_type, coalesce(s.document_id, s.source_message_id) as source_id,"
                        + " max(c.published_on) as newest from knowledge_chunks c join knowledge_sources s on s.id = c.knowledge_source_id"
                        + where + " group by s.source_type, coalesce(s.document_id, s.source_message_id)"
                        + " order by newest desc nulls last, source_id limit :limit",
                params,
                (rs, i) -> new SourceRef(KnowledgeSourceType.valueOf(rs.getString("source_type")), rs.getObject("source_id", UUID.class)));
    }

    /**
     * @return email id → documents attached to or downloaded from it, for the given emails
     */
    public Map<UUID, List<UUID>> documentsOfEmails(Collection<UUID> emailIds) {
        Map<UUID, List<UUID>> result = new HashMap<>();
        if (emailIds.isEmpty()) {
            return result;
        }
        jdbc.query("select distinct source_message_id, document_id from message_documents where source_message_id in (:ids)",
                new MapSqlParameterSource("ids", emailIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject(1, UUID.class), id -> new ArrayList<>()).add(rs.getObject(2, UUID.class));
                });
        return result;
    }

    private MapSqlParameterSource window(Instant from, Instant to) {
        return new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("version", version.current());
    }

    private static AvailableSource available(ResultSet rs, int row) throws SQLException {
        Date published = rs.getDate("published_on");
        Array tickers = rs.getArray("tickers");
        return new AvailableSource(rs.getObject("id", UUID.class), KnowledgeSourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_id", UUID.class), rs.getString("title"), rs.getString("brokerage"),
                published == null ? null : published.toLocalDate(), rs.getString("date_basis"), instant(rs, "sent_at"),
                instant(rs, "imported_at"), instant(rs, "first_indexed_at"), rs.getString("source_ticker"), rs.getString("report_type"),
                tickers == null ? List.of() : List.copyOf(Arrays.asList((String[]) tickers.getArray())), rs.getString("content_sha256"));
    }

    private String state(String status, String indexVersion) {
        if (status == null) {
            return "NOT_TRACKED";
        }
        if (status.equals("INDEXED")) {
            return version.current().equals(indexVersion) ? "INDEXED" : "OUTDATED";
        }
        return status;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * @param knowledgeSourceId the knowledge index entry
     * @param sourceId          document or email id
     * @param publishedOn       publication date, or the email sent date when {@code dateBasis} is EMAIL_SENT
     * @param sentAt            email sent date (emails only)
     * @param importedAt        when the email was imported or the document stored
     * @param firstIndexedAt    when the source first became searchable
     * @param reportType        document report type, if the user or download recorded one
     * @param contentSha256     fingerprint of the indexed text
     */
    public record AvailableSource(UUID knowledgeSourceId, KnowledgeSourceType sourceType, UUID sourceId, String title, String brokerage,
                                  LocalDate publishedOn, String dateBasis, Instant sentAt, Instant importedAt, Instant firstIndexedAt,
                                  String sourceTicker, String reportType, List<String> tickers, String contentSha256) {
    }

    /**
     * A picked document or email and whether it can be searched.
     *
     * @param knowledgeSourceId the index entry, {@code null} when the source was never tracked
     * @param state             INDEXED, NOT_TRACKED, PENDING, PROCESSING, FAILED, SKIPPED or OUTDATED
     */
    public record SourceState(KnowledgeSourceType sourceType, UUID sourceId, UUID knowledgeSourceId, String title, String brokerage,
                              LocalDate publishedOn, String dateBasis, Instant sentAt, Instant importedAt, String sourceTicker,
                              List<String> tickers, String state, String contentSha256) {

        public boolean searchable() {
            return "INDEXED".equals(state);
        }
    }

    /** A document ({@code DOCUMENT}) or email ({@code EMAIL}) by id. */
    public record SourceRef(KnowledgeSourceType sourceType, UUID sourceId) {
    }

    /**
     * @param state NOT_TRACKED, PENDING, PROCESSING, FAILED, SKIPPED or OUTDATED
     */
    public record PendingSource(KnowledgeSourceType sourceType, UUID sourceId, String title, String brokerage, Instant importedAt,
                                Instant sentAt, String state) {
    }
}

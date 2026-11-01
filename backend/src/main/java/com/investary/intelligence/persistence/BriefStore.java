package com.investary.intelligence.persistence;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.intelligence.domain.BriefAiStatus;
import com.investary.intelligence.domain.BriefSourceRole;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.Relevance;
import com.investary.intelligence.domain.ResearchState;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.EvidenceStatus;

/**
 * Briefs and their sources (JDBC). Brief rows are immutable: a new generation for the same scope and period
 * is a new revision in the same chain ({@code root_brief_id}, {@code revision}).
 */
@Repository
public class BriefStore {

    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    private static final String COLUMNS = """
            b.id, b.portfolio_id, b.brief_type, b.trigger_type, b.status, b.research_state, b.period_start, b.period_end, b.zone,
            b.business_date, b.partial_period, b.generated_at, b.source_cutoff, b.language, b.content, b.limitations, b.ai_status,
            b.model, b.prompt_version, b.index_version, b.config_version, b.input_fingerprint, b.snapshot_hash, b.evidence_status,
            b.error_code, b.error_summary, b.input_tokens, b.output_tokens, b.revision, b.root_brief_id, b.previous_revision_id,
            b.job_id""";

    /** Latest revision of each chain. */
    private static final String HEAD = "b.revision = (select max(r.revision) from briefs r where r.root_brief_id = b.root_brief_id)";

    private final NamedParameterJdbcTemplate jdbc;

    public BriefStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a brief and its sources; call inside a transaction. */
    public void insert(NewBrief brief, List<NewSource> sources) {
        jdbc.update("""
                insert into briefs (id, portfolio_id, brief_type, trigger_type, status, research_state, period_start, period_end, zone,
                                    business_date, partial_period, generated_at, source_cutoff, language, content, limitations, ai_status,
                                    model, prompt_version, index_version, config_version, input_fingerprint, portfolio_snapshot,
                                    snapshot_hash, evidence_status, error_code, error_summary, input_tokens, output_tokens, revision,
                                    root_brief_id, previous_revision_id, job_id)
                values (:id, :portfolioId, 'DAILY', :trigger, :status, :researchState, :start, :end, :zone, :businessDate, :partial,
                        :generatedAt, :cutoff, 'vi', cast(:content as jsonb), cast(:limitations as jsonb), :aiStatus, :model,
                        :promptVersion, :indexVersion, :configVersion, :fingerprint, cast(:snapshot as jsonb), :snapshotHash,
                        :evidenceStatus, :errorCode, :errorSummary, :inputTokens, :outputTokens, :revision, :rootId, :previousId, :jobId)""",
                new MapSqlParameterSource("id", brief.id())
                        .addValue("portfolioId", brief.portfolioId())
                        .addValue("trigger", brief.trigger().name())
                        .addValue("status", brief.status().name())
                        .addValue("researchState", brief.researchState() == null ? null : brief.researchState().name())
                        .addValue("start", Timestamp.from(brief.periodStart()))
                        .addValue("end", Timestamp.from(brief.periodEnd()))
                        .addValue("zone", brief.zone())
                        .addValue("businessDate", Date.valueOf(brief.businessDate()))
                        .addValue("partial", brief.partialPeriod())
                        .addValue("generatedAt", Timestamp.from(brief.generatedAt()))
                        .addValue("cutoff", Timestamp.from(brief.sourceCutoff()))
                        .addValue("content", brief.contentJson())
                        .addValue("limitations", brief.limitationsJson())
                        .addValue("aiStatus", brief.aiStatus().name())
                        .addValue("model", brief.model())
                        .addValue("promptVersion", brief.promptVersion())
                        .addValue("indexVersion", brief.indexVersion())
                        .addValue("configVersion", brief.configVersion())
                        .addValue("fingerprint", brief.inputFingerprint())
                        .addValue("snapshot", brief.portfolioSnapshotJson())
                        .addValue("snapshotHash", brief.snapshotHash())
                        .addValue("evidenceStatus", brief.evidenceStatus() == null ? null : brief.evidenceStatus().name())
                        .addValue("errorCode", brief.errorCode())
                        .addValue("errorSummary", brief.errorSummary())
                        .addValue("inputTokens", brief.inputTokens())
                        .addValue("outputTokens", brief.outputTokens())
                        .addValue("revision", brief.revision())
                        .addValue("rootId", brief.rootBriefId())
                        .addValue("previousId", brief.previousRevisionId())
                        .addValue("jobId", brief.jobId()));
        int position = 0;
        for (NewSource source : sources) {
            jdbc.update("""
                    insert into brief_sources (id, brief_id, position, role, label, source_type, document_id, source_message_id, chunk_id,
                                               chunk_sha256, title, brokerage, published_on, date_basis, sent_at, imported_at, indexed_at,
                                               page_start, page_end, section, location, excerpt, symbols, relevance, index_state,
                                               old_publication, late_arrival, received_via)
                    values (:id, :briefId, :position, :role, :label, :sourceType,
                            (select id from document where id = :documentId), (select id from source_messages where id = :messageId),
                            :chunkId, :chunkSha, :title, :brokerage, :publishedOn, :dateBasis, :sentAt, :importedAt, :indexedAt,
                            :pageStart, :pageEnd, :section, :location, :excerpt, cast(:symbols as text[]), :relevance, :indexState,
                            :oldPublication, :lateArrival, cast(:receivedVia as uuid[]))""",
                    new MapSqlParameterSource("id", UUID.randomUUID())
                            .addValue("briefId", brief.id())
                            .addValue("position", position++)
                            .addValue("role", source.role().name())
                            .addValue("label", source.label())
                            .addValue("sourceType", source.sourceType().name())
                            .addValue("documentId", source.sourceType() == KnowledgeSourceType.DOCUMENT ? source.sourceId() : null)
                            .addValue("messageId", source.sourceType() == KnowledgeSourceType.EMAIL ? source.sourceId() : null)
                            .addValue("chunkId", source.chunkId())
                            .addValue("chunkSha", source.chunkSha256())
                            .addValue("title", truncate(source.title(), 1000))
                            .addValue("brokerage", truncate(source.brokerage(), 200))
                            .addValue("publishedOn", source.publishedOn() == null ? null : Date.valueOf(source.publishedOn()))
                            .addValue("dateBasis", source.dateBasis())
                            .addValue("sentAt", timestamp(source.sentAt()))
                            .addValue("importedAt", timestamp(source.importedAt()))
                            .addValue("indexedAt", timestamp(source.indexedAt()))
                            .addValue("pageStart", source.pageStart())
                            .addValue("pageEnd", source.pageEnd())
                            .addValue("section", truncate(source.section(), 500))
                            .addValue("location", truncate(source.location(), 500))
                            .addValue("excerpt", source.excerpt())
                            .addValue("symbols", pgArray(source.symbols()))
                            .addValue("relevance", source.relevance() == null ? null : source.relevance().name())
                            .addValue("indexState", source.indexState())
                            .addValue("oldPublication", source.oldPublication())
                            .addValue("lateArrival", source.lateArrival())
                            .addValue("receivedVia", pgArray(source.receivedVia().stream().map(UUID::toString).toList())));
        }
    }

    public Optional<BriefRow> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from briefs b where b.id = :id", new MapSqlParameterSource("id", id), BriefStore::row)
                .stream().findFirst();
    }

    /** The latest revision of the chain for this scope and period, if any. */
    public Optional<BriefRow> head(UUID portfolioId, Instant periodStart, Instant periodEnd) {
        return jdbc.query("select " + COLUMNS + """
                 from briefs b
                where coalesce(b.portfolio_id, cast(:zero as uuid)) = coalesce(cast(:portfolioId as uuid), cast(:zero as uuid))
                  and b.period_start = :start and b.period_end = :end
                order by b.revision desc, b.created_at desc
                limit 1""",
                new MapSqlParameterSource("portfolioId", portfolioId).addValue("zero", ZERO_UUID)
                        .addValue("start", Timestamp.from(periodStart)).addValue("end", Timestamp.from(periodEnd)),
                BriefStore::row).stream().findFirst();
    }

    /** The latest revision of the chain {@code briefId} belongs to. */
    public Optional<BriefRow> headOfChain(UUID briefId) {
        return jdbc.query("select " + COLUMNS + """
                 from briefs b
                where b.root_brief_id = (select root_brief_id from briefs where id = :id)
                order by b.revision desc
                limit 1""",
                new MapSqlParameterSource("id", briefId), BriefStore::row).stream().findFirst();
    }

    public List<RevisionRow> revisions(UUID rootBriefId) {
        return jdbc.query("""
                select id, revision, trigger_type, status, generated_at from briefs where root_brief_id = :root order by revision desc""",
                new MapSqlParameterSource("root", rootBriefId),
                (rs, i) -> new RevisionRow(rs.getObject("id", UUID.class), rs.getInt("revision"),
                        BriefTrigger.valueOf(rs.getString("trigger_type")), BriefStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("generated_at").toInstant()));
    }

    /**
     * Latest revision per chain, newest period first. SKIPPED briefs are only listed when asked for.
     */
    public List<BriefRow> list(ListFilter filter, int page, int size) {
        return jdbc.query("select " + COLUMNS + " from briefs b where " + HEAD + " and " + FILTER
                        + " order by b.period_end desc, b.generated_at desc, b.id limit :limit offset :offset",
                filterParams(filter).addValue("limit", size).addValue("offset", (long) page * size), BriefStore::row);
    }

    public long count(ListFilter filter) {
        Long count = jdbc.queryForObject("select count(*) from briefs b where " + HEAD + " and " + FILTER, filterParams(filter), Long.class);
        return count == null ? 0 : count;
    }

    public List<SourceRow> sources(UUID briefId) {
        return jdbc.query("""
                select role, label, source_type, coalesce(document_id, source_message_id) as source_id, chunk_id, chunk_sha256, title,
                       brokerage, published_on, date_basis, sent_at, imported_at, indexed_at, page_start, page_end, section, location,
                       excerpt, symbols, relevance, index_state, old_publication, late_arrival, received_via
                from brief_sources where brief_id = :id order by position""",
                new MapSqlParameterSource("id", briefId), BriefStore::source);
    }

    private static final String FILTER = """
            (cast(:portfolioId as uuid) is null or b.portfolio_id = :portfolioId)
              and (cast(:from as date) is null or b.business_date >= :from)
              and (cast(:to as date) is null or b.business_date <= :to)
              and (case when cast(:status as varchar) is null then b.status <> 'SKIPPED' else b.status = :status end)""";

    private static MapSqlParameterSource filterParams(ListFilter filter) {
        return new MapSqlParameterSource()
                .addValue("portfolioId", filter.portfolioId())
                .addValue("from", filter.from() == null ? null : Date.valueOf(filter.from()))
                .addValue("to", filter.to() == null ? null : Date.valueOf(filter.to()))
                .addValue("status", filter.status() == null ? null : filter.status().name());
    }

    private static BriefRow row(ResultSet rs, int i) throws SQLException {
        String researchState = rs.getString("research_state");
        String evidence = rs.getString("evidence_status");
        return new BriefRow(
                rs.getObject("id", UUID.class),
                rs.getObject("portfolio_id", UUID.class),
                rs.getString("brief_type"),
                BriefTrigger.valueOf(rs.getString("trigger_type")),
                BriefStatus.valueOf(rs.getString("status")),
                researchState == null ? null : ResearchState.valueOf(researchState),
                instant(rs, "period_start"),
                instant(rs, "period_end"),
                rs.getString("zone"),
                rs.getDate("business_date").toLocalDate(),
                rs.getBoolean("partial_period"),
                instant(rs, "generated_at"),
                instant(rs, "source_cutoff"),
                rs.getString("language"),
                rs.getString("content"),
                rs.getString("limitations"),
                BriefAiStatus.valueOf(rs.getString("ai_status")),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("index_version"),
                rs.getString("config_version"),
                rs.getString("input_fingerprint"),
                rs.getString("snapshot_hash"),
                evidence == null ? null : EvidenceStatus.valueOf(evidence),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                rs.getInt("revision"),
                rs.getObject("root_brief_id", UUID.class),
                rs.getObject("previous_revision_id", UUID.class),
                rs.getObject("job_id", UUID.class));
    }

    private static SourceRow source(ResultSet rs, int i) throws SQLException {
        Date published = rs.getDate("published_on");
        String relevance = rs.getString("relevance");
        Array symbols = rs.getArray("symbols");
        Array via = rs.getArray("received_via");
        return new SourceRow(
                BriefSourceRole.valueOf(rs.getString("role")),
                rs.getString("label"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_id", UUID.class),
                rs.getObject("chunk_id", UUID.class),
                rs.getString("chunk_sha256"),
                rs.getString("title"),
                rs.getString("brokerage"),
                published == null ? null : published.toLocalDate(),
                rs.getString("date_basis"),
                instant(rs, "sent_at"),
                instant(rs, "imported_at"),
                instant(rs, "indexed_at"),
                rs.getObject("page_start", Integer.class),
                rs.getObject("page_end", Integer.class),
                rs.getString("section"),
                rs.getString("location"),
                rs.getString("excerpt"),
                symbols == null ? List.of() : List.of((String[]) symbols.getArray()),
                relevance == null ? null : Relevance.valueOf(relevance),
                rs.getString("index_state"),
                rs.getBoolean("old_publication"),
                rs.getBoolean("late_arrival"),
                via == null ? List.of() : Arrays.asList((UUID[]) via.getArray()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** Array literal for {@code cast(:p as text[])} with every element quoted. */
    private static String pgArray(List<String> values) {
        return values.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record ListFilter(UUID portfolioId, LocalDate from, LocalDate to, BriefStatus status) {
    }

    public record NewBrief(UUID id, UUID portfolioId, BriefTrigger trigger, BriefStatus status, ResearchState researchState,
                           Instant periodStart, Instant periodEnd, String zone, LocalDate businessDate, boolean partialPeriod,
                           Instant generatedAt, Instant sourceCutoff, String contentJson, String limitationsJson, BriefAiStatus aiStatus,
                           String model, String promptVersion, String indexVersion, String configVersion, String inputFingerprint,
                           String portfolioSnapshotJson, String snapshotHash, EvidenceStatus evidenceStatus, String errorCode,
                           String errorSummary, Integer inputTokens, Integer outputTokens, int revision, UUID rootBriefId,
                           UUID previousRevisionId, UUID jobId) {
    }

    public record NewSource(BriefSourceRole role, String label, KnowledgeSourceType sourceType, UUID sourceId, UUID chunkId,
                            String chunkSha256, String title, String brokerage, LocalDate publishedOn, String dateBasis, Instant sentAt,
                            Instant importedAt, Instant indexedAt, Integer pageStart, Integer pageEnd, String section, String location,
                            String excerpt, List<String> symbols, Relevance relevance, String indexState, boolean oldPublication,
                            boolean lateArrival, List<UUID> receivedVia) {
    }

    public record BriefRow(UUID id, UUID portfolioId, String briefType, BriefTrigger trigger, BriefStatus status,
                           ResearchState researchState, Instant periodStart, Instant periodEnd, String zone, LocalDate businessDate,
                           boolean partialPeriod, Instant generatedAt, Instant sourceCutoff, String language, String contentJson,
                           String limitationsJson, BriefAiStatus aiStatus, String model, String promptVersion, String indexVersion,
                           String configVersion, String inputFingerprint, String snapshotHash, EvidenceStatus evidenceStatus,
                           String errorCode, String errorSummary, Integer inputTokens, Integer outputTokens, int revision,
                           UUID rootBriefId, UUID previousRevisionId, UUID jobId) {
    }

    /**
     * @param sourceId null when the source was deleted after the brief was written
     */
    public record SourceRow(BriefSourceRole role, String label, KnowledgeSourceType sourceType, UUID sourceId, UUID chunkId,
                            String chunkSha256, String title, String brokerage, LocalDate publishedOn, String dateBasis, Instant sentAt,
                            Instant importedAt, Instant indexedAt, Integer pageStart, Integer pageEnd, String section, String location,
                            String excerpt, List<String> symbols, Relevance relevance, String indexState, boolean oldPublication,
                            boolean lateArrival, List<UUID> receivedVia) {
    }

    public record RevisionRow(UUID id, int revision, BriefTrigger trigger, BriefStatus status, Instant generatedAt) {
    }
}

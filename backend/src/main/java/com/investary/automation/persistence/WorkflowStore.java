package com.investary.automation.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.domain.WorkflowTrigger;

/**
 * Research workflows, their stages and portfolio matches. The source rows (emails, documents) belong to other
 * modules; workflows reference them by foreign key and disappear with them ({@code ON DELETE CASCADE}).
 */
@Repository
public class WorkflowStore {

    private static final String WORKFLOW_COLUMNS = """
            id, source_type, source_message_id, document_id, status, trigger_type, processing_version, gmail_sync_run_id,
            auto_downloads_requested, started_at, completed_at, updated_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the workflow and its PENDING stages unless the source already has one.
     *
     * @return the new workflow id, or empty if the source already had a workflow
     */
    public Optional<UUID> create(WorkflowSourceType type, UUID sourceId, WorkflowTrigger trigger, String version, UUID syncRunId,
                                 Instant now) {
        String column = type == WorkflowSourceType.EMAIL ? "source_message_id" : "document_id";
        List<UUID> created = jdbc.queryForList("""
                insert into research_workflows (id, source_type, %s, status, trigger_type, processing_version, gmail_sync_run_id,
                                                started_at, updated_at)
                values (:id, :type, :sourceId, 'RUNNING', :trigger, :version, :syncRunId, :now, :now)
                on conflict do nothing
                returning id""".formatted(column),
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("type", type.name())
                        .addValue("sourceId", sourceId)
                        .addValue("trigger", trigger.name())
                        .addValue("version", version)
                        .addValue("syncRunId", syncRunId)
                        .addValue("now", Timestamp.from(now)),
                UUID.class);
        if (created.isEmpty()) {
            return Optional.empty();
        }
        UUID id = created.getFirst();
        for (WorkflowStage stage : WorkflowStage.values()) {
            jdbc.update("insert into workflow_stages (workflow_id, stage, status) values (:id, :stage, 'PENDING')",
                    new MapSqlParameterSource("id", id).addValue("stage", stage.name()));
        }
        return Optional.of(id);
    }

    public Optional<WorkflowRow> find(UUID id) {
        return jdbc.query("select " + WORKFLOW_COLUMNS + " from research_workflows where id = :id",
                new MapSqlParameterSource("id", id), WorkflowStore::workflow).stream().findFirst();
    }

    /** Locks the workflow row for the rest of the transaction (serialises status finalisation). */
    public Optional<WorkflowRow> lock(UUID id) {
        return jdbc.query("select " + WORKFLOW_COLUMNS + " from research_workflows where id = :id for update",
                new MapSqlParameterSource("id", id), WorkflowStore::workflow).stream().findFirst();
    }

    public Optional<WorkflowRow> findBySource(UUID sourceId) {
        return jdbc.query("select " + WORKFLOW_COLUMNS + " from research_workflows where source_message_id = :id or document_id = :id",
                new MapSqlParameterSource("id", sourceId), WorkflowStore::workflow).stream().findFirst();
    }

    public void updateStatus(UUID id, StageStatus status, Instant completedAt) {
        jdbc.update("""
                update research_workflows set status = :status, completed_at = :completedAt, updated_at = now()
                where id = :id""",
                new MapSqlParameterSource("id", id).addValue("status", status.name())
                        .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt)));
    }

    public void linkSyncRun(UUID id, UUID syncRunId) {
        jdbc.update("update research_workflows set gmail_sync_run_id = :run where id = :id and gmail_sync_run_id is null",
                new MapSqlParameterSource("id", id).addValue("run", syncRunId));
    }

    public void addAutoDownloads(UUID id, int count) {
        jdbc.update("update research_workflows set auto_downloads_requested = auto_downloads_requested + :n, updated_at = now() where id = :id",
                new MapSqlParameterSource("id", id).addValue("n", count));
    }

    /** Automatic downloads already requested by the workflows of one Gmail sync run. */
    public int autoDownloadsForSyncRun(UUID syncRunId) {
        Integer total = jdbc.queryForObject("""
                select coalesce(sum(auto_downloads_requested), 0) from research_workflows where gmail_sync_run_id = :run""",
                new MapSqlParameterSource("run", syncRunId), Integer.class);
        return total == null ? 0 : total;
    }

    public List<StageRow> stages(UUID workflowId) {
        return jdbc.query("""
                select stage, status, reason, counts::text as counts, started_at, completed_at, updated_at
                from workflow_stages where workflow_id = :id""",
                new MapSqlParameterSource("id", workflowId),
                (rs, i) -> new StageRow(WorkflowStage.valueOf(rs.getString("stage")), StageStatus.valueOf(rs.getString("status")),
                        rs.getString("reason"), rs.getString("counts"), instant(rs, "started_at"), instant(rs, "completed_at"),
                        instant(rs, "updated_at")))
                .stream().sorted((a, b) -> a.stage().compareTo(b.stage())).toList();
    }

    public Optional<StageRow> stage(UUID workflowId, WorkflowStage stage) {
        return stages(workflowId).stream().filter(s -> s.stage() == stage).findFirst();
    }

    /**
     * @param countsJson JSON object of counters, or null to keep the current ones
     */
    public void updateStage(UUID workflowId, WorkflowStage stage, StageStatus status, String reason, String countsJson) {
        jdbc.update("""
                update workflow_stages
                set status = :status, reason = :reason, counts = coalesce(cast(:counts as jsonb), counts),
                    started_at = case when :status = 'PENDING' then null else coalesce(started_at, now()) end,
                    completed_at = case when :status in ('COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED') then now() else null end,
                    updated_at = now()
                where workflow_id = :id and stage = :stage""",
                new MapSqlParameterSource("id", workflowId).addValue("stage", stage.name()).addValue("status", status.name())
                        .addValue("reason", reason).addValue("counts", countsJson));
    }

    /** Replaces the portfolio matches of a workflow. */
    public void replaceMatches(UUID workflowId, List<MatchRow> matches, Instant now) {
        var params = new MapSqlParameterSource("id", workflowId);
        jdbc.update("delete from workflow_portfolio_matches where workflow_id = :id", params);
        for (MatchRow match : matches) {
            jdbc.update("""
                    insert into workflow_portfolio_matches (id, workflow_id, portfolio_id, instrument_id, symbol, match_kind,
                                                            published_on, snapshot_hash, matched_at)
                    values (:id, :workflowId, :portfolioId, :instrumentId, :symbol, :kind, :publishedOn, :snapshotHash, :now)""",
                    new MapSqlParameterSource("id", UUID.randomUUID())
                            .addValue("workflowId", workflowId)
                            .addValue("portfolioId", match.portfolioId())
                            .addValue("instrumentId", match.instrumentId())
                            .addValue("symbol", match.symbol())
                            .addValue("kind", match.matchKind())
                            .addValue("publishedOn", match.publishedOn())
                            .addValue("snapshotHash", match.snapshotHash())
                            .addValue("now", Timestamp.from(now)));
        }
    }

    public List<MatchRow> matches(UUID workflowId) {
        return jdbc.query("""
                select portfolio_id, instrument_id, symbol, match_kind, published_on, snapshot_hash, matched_at
                from workflow_portfolio_matches where workflow_id = :id order by portfolio_id, symbol""",
                new MapSqlParameterSource("id", workflowId),
                (rs, i) -> new MatchRow(rs.getObject("portfolio_id", UUID.class), rs.getObject("instrument_id", UUID.class),
                        rs.getString("symbol"), rs.getString("match_kind"), rs.getObject("published_on", LocalDate.class),
                        rs.getString("snapshot_hash"), instant(rs, "matched_at")));
    }

    /** When workflows became available; older sources are processed on request only. */
    public Instant activatedAt() {
        return jdbc.queryForObject("select activated_at from workflow_settings where id = 1", new MapSqlParameterSource(),
                (rs, i) -> rs.getTimestamp(1).toInstant());
    }

    /** Emails imported since activation, older than {@code settledBefore}, that have no workflow (missed events). */
    public List<UUID> emailsWithoutWorkflow(Instant settledBefore, int limit) {
        return jdbc.queryForList("""
                select m.id from source_messages m
                where m.imported_at >= (select activated_at from workflow_settings where id = 1)
                  and m.imported_at < :before
                  and not exists (select 1 from research_workflows w where w.source_message_id = m.id)
                order by m.imported_at
                limit :limit""",
                new MapSqlParameterSource("before", Timestamp.from(settledBefore)).addValue("limit", limit), UUID.class);
    }

    /**
     * Library documents uploaded since activation, older than {@code settledBefore}, with no workflow and not
     * referenced by any email (attachments and downloaded reports belong to their email's workflow).
     */
    public List<UUID> libraryDocumentsWithoutWorkflow(Instant settledBefore, int limit) {
        return jdbc.queryForList("""
                select d.id from document d
                where d.uploaded_at >= (select activated_at from workflow_settings where id = 1)
                  and d.uploaded_at < :before
                  and not exists (select 1 from research_workflows w where w.document_id = d.id)
                  and not exists (select 1 from message_documents md where md.document_id = d.id)
                  and not exists (select 1 from source_attachments a where a.document_id = d.id)
                  and not exists (select 1 from source_links l where l.document_id = d.id)
                order by d.uploaded_at
                limit :limit""",
                new MapSqlParameterSource("before", Timestamp.from(settledBefore)).addValue("limit", limit), UUID.class);
    }

    /**
     * The Gmail source of an email: the sync run that imported it and whether its matched rule allows
     * automatic PDF downloads. Empty for uploaded EML files.
     */
    public Optional<GmailSource> gmailSource(UUID emailId) {
        return jdbc.query("""
                select gm.sync_run_id, coalesce(r.auto_pdf_download and r.enabled, false) as auto_download, r.name
                from gmail_messages gm
                left join email_source_rules r on r.id = gm.source_rule_id
                where gm.source_message_id = :id
                order by gm.processed_at
                limit 1""",
                new MapSqlParameterSource("id", emailId),
                (rs, i) -> new GmailSource(rs.getObject("sync_run_id", UUID.class), rs.getBoolean("auto_download"), rs.getString("name")))
                .stream().findFirst();
    }

    private static WorkflowRow workflow(ResultSet rs, int i) throws SQLException {
        UUID messageId = rs.getObject("source_message_id", UUID.class);
        return new WorkflowRow(
                rs.getObject("id", UUID.class),
                WorkflowSourceType.valueOf(rs.getString("source_type")),
                messageId != null ? messageId : rs.getObject("document_id", UUID.class),
                StageStatus.valueOf(rs.getString("status")),
                WorkflowTrigger.valueOf(rs.getString("trigger_type")),
                rs.getString("processing_version"),
                rs.getObject("gmail_sync_run_id", UUID.class),
                rs.getInt("auto_downloads_requested"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record WorkflowRow(UUID id, WorkflowSourceType sourceType, UUID sourceId, StageStatus status, WorkflowTrigger trigger,
                              String processingVersion, UUID gmailSyncRunId, int autoDownloadsRequested, Instant startedAt,
                              Instant completedAt, Instant updatedAt) {
    }

    public record StageRow(WorkflowStage stage, StageStatus status, String reason, String counts, Instant startedAt,
                           Instant completedAt, Instant updatedAt) {
    }

    public record MatchRow(UUID portfolioId, UUID instrumentId, String symbol, String matchKind, LocalDate publishedOn,
                           String snapshotHash, Instant matchedAt) {
    }

    /**
     * @param syncRunId    Gmail sync run that imported the email (may be null)
     * @param autoDownload the matched source rule is enabled and allows automatic PDF downloads
     */
    public record GmailSource(UUID syncRunId, boolean autoDownload, String ruleName) {
    }
}

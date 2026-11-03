package com.investary.automation.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only counts for the Automation Center overview. Like the workflow reconcile, it reads the record tables
 * of the owning modules directly (emails, downloads, knowledge sources, assessments) and never writes them, so
 * the dashboard shows exactly what is stored.
 */
@Repository
public class AutomationStatsStore {

    private final NamedParameterJdbcTemplate jdbc;

    public AutomationStatsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long emailsImported(Instant from, Instant to) {
        return count("select count(*) from source_messages where imported_at >= :from and imported_at < :to", from, to);
    }

    /** Email workflows that finished COMPLETED or PARTIAL in the window. */
    public long emailsProcessed(Instant from, Instant to) {
        return count("""
                select count(*) from research_workflows
                where source_type = 'EMAIL' and status in ('COMPLETED', 'PARTIAL') and completed_at >= :from and completed_at < :to""",
                from, to);
    }

    public long reportsDownloaded(Instant from, Instant to) {
        return count("select count(*) from download_jobs where status = 'SUCCEEDED' and updated_at >= :from and updated_at < :to", from, to);
    }

    public long downloadsFailed(Instant from, Instant to) {
        return count("select count(*) from download_jobs where status = 'FAILED' and updated_at >= :from and updated_at < :to", from, to);
    }

    public long downloadsActive() {
        return count("select count(*) from download_jobs where status in ('PENDING', 'RUNNING')", null, null);
    }

    /** Documents that first became searchable in the window. */
    public long documentsIndexed(Instant from, Instant to) {
        return count("""
                select count(*) from knowledge_sources
                where source_type = 'DOCUMENT' and first_indexed_at >= :from and first_indexed_at < :to""", from, to);
    }

    public long indexFailed(Instant from, Instant to) {
        return count("select count(*) from knowledge_sources where status = 'FAILED' and updated_at >= :from and updated_at < :to", from, to);
    }

    /** Sources waiting for indexing that were not touched since {@code before}. */
    public long indexPendingSince(Instant before) {
        return count("select count(*) from knowledge_sources where status in ('PENDING', 'PROCESSING') and updated_at < :to", null, before);
    }

    public long assessmentsCreated(Instant from, Instant to) {
        return count("select count(*) from impact_assessments where created_at >= :from and created_at < :to", from, to);
    }

    /** Automatic assessments (started by a workflow) created in the window. */
    public long automaticAssessmentsCreated(Instant from, Instant to) {
        return count("""
                select count(*) from impact_assessments
                where trigger_source_id is not null and created_at >= :from and created_at < :to""", from, to);
    }

    /** Brief revisions per Asia/Ho_Chi_Minh day in [from, to]; {@code aiOnly} counts those whose model part ran. */
    public Map<LocalDate, Long> briefsPerDay(LocalDate from, LocalDate to, boolean aiOnly) {
        return perDay("select (generated_at at time zone 'Asia/Ho_Chi_Minh')::date as day, count(*) as n from briefs where status <> 'SKIPPED'"
                + (aiOnly ? " and ai_status in ('COMPLETED', 'FAILED')" : "")
                + " and (generated_at at time zone 'Asia/Ho_Chi_Minh')::date between :from and :to group by 1", from, to);
    }

    /** Impact assessments per Asia/Ho_Chi_Minh day in [from, to]; {@code automaticOnly} counts those started by a workflow. */
    public Map<LocalDate, Long> assessmentsPerDay(LocalDate from, LocalDate to, boolean automaticOnly) {
        return perDay("select (created_at at time zone 'Asia/Ho_Chi_Minh')::date as day, count(*) as n from impact_assessments where true"
                + (automaticOnly ? " and trigger_source_id is not null" : "")
                + " and (created_at at time zone 'Asia/Ho_Chi_Minh')::date between :from and :to group by 1", from, to);
    }

    private Map<LocalDate, Long> perDay(String sql, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> counts = new TreeMap<>();
        jdbc.query(sql, new MapSqlParameterSource("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)),
                rs -> {
                    counts.put(rs.getDate("day").toLocalDate(), rs.getLong("n"));
                });
        return counts;
    }

    public Instant lastEmailImportedAt() {
        return latest("select max(imported_at) from source_messages");
    }

    public Instant lastDownloadAt() {
        return latest("select max(updated_at) from download_jobs where status in ('SUCCEEDED', 'FAILED')");
    }

    public Instant lastIndexedAt() {
        return latest("select max(completed_at) from knowledge_sources where status = 'INDEXED'");
    }

    private long count(String sql, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from == null ? null : Timestamp.from(from))
                .addValue("to", to == null ? null : Timestamp.from(to));
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    private Instant latest(String sql) {
        Timestamp value = jdbc.queryForObject(sql, new MapSqlParameterSource(), Timestamp.class);
        return value == null ? null : value.toInstant();
    }
}

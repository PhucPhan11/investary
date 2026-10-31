package com.investary.intelligence.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;

import com.investary.knowledge.assistant.EvidenceStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A saved portfolio impact assessment. Immutable: the portfolio snapshot and evidence are copies taken
 * at creation, so later changes make it stale instead of silently changing it.
 */
@Entity
@Table(name = "impact_assessments")
public class ImpactAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JSON {@code AssessmentSnapshot}: holdings and totals when the assessment was made. */
    @Column(name = "portfolio_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String portfolioSnapshot;

    @Column(name = "snapshot_hash", nullable = false, updatable = false)
    private String snapshotHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status", nullable = false, updatable = false)
    private AiStatus aiStatus;

    @Column(name = "model", updatable = false)
    private String model;

    @Column(name = "overall_summary", updatable = false)
    private String overallSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_evidence_status", updatable = false)
    private EvidenceStatus overallEvidenceStatus;

    /** JSON array of strings. */
    @Column(name = "warnings", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String warnings;

    /** Set only for assessments created by a research workflow: the source that triggered it. */
    @Column(name = "trigger_source_type", updatable = false)
    private String triggerSourceType;

    @Column(name = "trigger_source_id", updatable = false)
    private UUID triggerSourceId;

    /** Fingerprint of the trigger source's content (email and its documents) at assessment time. */
    @Column(name = "trigger_content_sha256", updatable = false)
    private String triggerContentSha256;

    @Column(name = "prompt_version", updatable = false)
    private String promptVersion;

    @Column(name = "index_version", updatable = false)
    private String indexVersion;

    @Column(name = "workflow_job_id", updatable = false)
    private UUID workflowJobId;

    protected ImpactAssessment() {
        // for JPA
    }

    public ImpactAssessment(UUID portfolioId, Instant createdAt, String portfolioSnapshot, String snapshotHash, AiStatus aiStatus,
                            String model, String overallSummary, EvidenceStatus overallEvidenceStatus, String warnings) {
        this.portfolioId = portfolioId;
        this.createdAt = createdAt;
        this.portfolioSnapshot = portfolioSnapshot;
        this.snapshotHash = snapshotHash;
        this.aiStatus = aiStatus;
        this.model = model;
        this.overallSummary = overallSummary;
        this.overallEvidenceStatus = overallEvidenceStatus;
        this.warnings = warnings;
    }

    /** Records versions and, for workflow assessments, the trigger; call before the first save. */
    public void recordProvenance(String promptVersion, String indexVersion, String triggerSourceType, UUID triggerSourceId,
                                 String triggerContentSha256, UUID workflowJobId) {
        this.promptVersion = promptVersion;
        this.indexVersion = indexVersion;
        this.triggerSourceType = triggerSourceType;
        this.triggerSourceId = triggerSourceId;
        this.triggerContentSha256 = triggerContentSha256;
        this.workflowJobId = workflowJobId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPortfolioSnapshot() {
        return portfolioSnapshot;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public AiStatus getAiStatus() {
        return aiStatus;
    }

    public String getModel() {
        return model;
    }

    public String getOverallSummary() {
        return overallSummary;
    }

    public EvidenceStatus getOverallEvidenceStatus() {
        return overallEvidenceStatus;
    }

    public String getWarnings() {
        return warnings;
    }

    public String getTriggerSourceType() {
        return triggerSourceType;
    }

    public UUID getTriggerSourceId() {
        return triggerSourceId;
    }

    public String getTriggerContentSha256() {
        return triggerContentSha256;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public UUID getWorkflowJobId() {
        return workflowJobId;
    }
}

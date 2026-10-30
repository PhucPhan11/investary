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
}

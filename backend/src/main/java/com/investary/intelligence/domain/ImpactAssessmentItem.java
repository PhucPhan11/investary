package com.investary.intelligence.domain;

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
 * The assessment of one holding. {@code deterministicCategory} comes from evidence matching alone;
 * {@code category} is the validated AI interpretation, or the same value when no AI was used.
 */
@Entity
@Table(name = "impact_assessment_items")
public class ImpactAssessmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "instrument_id", nullable = false, updatable = false)
    private UUID instrumentId;

    @Column(name = "symbol", nullable = false, updatable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "deterministic_category", nullable = false, updatable = false)
    private ImpactCategory deterministicCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false)
    private ImpactCategory category;

    @Column(name = "summary", updatable = false)
    private String summary;

    /** Whether {@code category} and {@code summary} are a validated model interpretation. */
    @Column(name = "ai_interpretation", nullable = false, updatable = false)
    private boolean aiInterpretation;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", nullable = false, updatable = false)
    private EvidenceStatus evidenceStatus;

    /** JSON array of {@code EvidenceReference}. */
    @Column(name = "evidence", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String evidence;

    protected ImpactAssessmentItem() {
        // for JPA
    }

    public ImpactAssessmentItem(UUID assessmentId, int position, UUID instrumentId, String symbol,
                                ImpactCategory deterministicCategory, ImpactCategory category, String summary,
                                boolean aiInterpretation, EvidenceStatus evidenceStatus, String evidence) {
        this.assessmentId = assessmentId;
        this.position = position;
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.deterministicCategory = deterministicCategory;
        this.category = category;
        this.summary = summary;
        this.aiInterpretation = aiInterpretation;
        this.evidenceStatus = evidenceStatus;
        this.evidence = evidence;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAssessmentId() {
        return assessmentId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getInstrumentId() {
        return instrumentId;
    }

    public String getSymbol() {
        return symbol;
    }

    public ImpactCategory getDeterministicCategory() {
        return deterministicCategory;
    }

    public ImpactCategory getCategory() {
        return category;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isAiInterpretation() {
        return aiInterpretation;
    }

    public EvidenceStatus getEvidenceStatus() {
        return evidenceStatus;
    }

    public String getEvidence() {
        return evidence;
    }
}

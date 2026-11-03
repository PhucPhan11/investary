package com.investary.journal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The portfolio and investor context of a decision, written once when it is finalized. A database trigger rejects
 * updates and deletes.
 */
@Entity
@Immutable
@Table(name = "decision_portfolio_snapshots")
public class DecisionPortfolioSnapshot {

    @Id
    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    @Column(name = "valuation_date", nullable = false, updatable = false)
    private LocalDate valuationDate;

    /** JSON {@code DecisionSnapshot}. */
    @Column(name = "snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String snapshot;

    protected DecisionPortfolioSnapshot() {
        // for JPA
    }

    public DecisionPortfolioSnapshot(UUID decisionId, Instant takenAt, LocalDate valuationDate, String snapshot) {
        this.decisionId = decisionId;
        this.takenAt = takenAt;
        this.valuationDate = valuationDate;
        this.snapshot = snapshot;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public String getSnapshot() {
        return snapshot;
    }
}

package com.investary.journal.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One entry of a decision's append-only audit trail. A database trigger rejects updates and deletes.
 */
@Entity
@Immutable
@Table(name = "decision_revisions")
public class DecisionRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private RevisionKind kind;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", nullable = false, updatable = false)
    private DecisionStatus statusAfter;

    /** JSON object: {@code {"field": {"from": …, "to": …}}} or the attached, removed or linked item. */
    @Column(name = "changes", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String changes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DecisionRevision() {
        // for JPA
    }

    public DecisionRevision(UUID decisionId, int sequence, RevisionKind kind, String reason, DecisionStatus statusAfter, String changes,
                            Instant createdAt) {
        this.decisionId = decisionId;
        this.sequence = sequence;
        this.kind = kind;
        this.reason = reason;
        this.statusAfter = statusAfter;
        this.changes = changes;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public int getSequence() {
        return sequence;
    }

    public RevisionKind getKind() {
        return kind;
    }

    public String getReason() {
        return reason;
    }

    public DecisionStatus getStatusAfter() {
        return statusAfter;
    }

    public String getChanges() {
        return changes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

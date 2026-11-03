package com.investary.journal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A source the user attached to a decision. The title, date, location and excerpt are copies taken when it was
 * attached. The reference column of its kind is set to {@code null} by the database when the source is deleted;
 * {@code sourceId} keeps the original id. Evidence is never deleted, only marked removed.
 */
@Entity
@Table(name = "decision_evidence")
public class DecisionEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private EvidenceKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private EvidenceRole role;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    // Written once; the database may set them to null (ON DELETE SET NULL), so they are not updatable from here.
    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "source_message_id", updatable = false)
    private UUID sourceMessageId;

    @Column(name = "research_item_id", updatable = false)
    private UUID researchItemId;

    @Column(name = "chunk_id", updatable = false)
    private UUID chunkId;

    @Column(name = "assessment_id", updatable = false)
    private UUID assessmentId;

    @Column(name = "content_sha256", updatable = false)
    private String contentSha256;

    @Column(name = "title", updatable = false)
    private String title;

    @Column(name = "publisher", updatable = false)
    private String publisher;

    @Column(name = "published_on", updatable = false)
    private LocalDate publishedOn;

    @Column(name = "location", updatable = false)
    private String location;

    @Column(name = "excerpt", updatable = false)
    private String excerpt;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "attached_at", nullable = false, updatable = false)
    private Instant attachedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_reason")
    private String removedReason;

    protected DecisionEvidence() {
        // for JPA
    }

    public DecisionEvidence(UUID decisionId, EvidenceKind kind, EvidenceRole role, UUID sourceId, String contentSha256, String title,
                            String publisher, LocalDate publishedOn, String location, String excerpt, String note, Instant attachedAt) {
        this.decisionId = decisionId;
        this.kind = kind;
        this.role = role;
        this.sourceId = sourceId;
        switch (kind) {
            case DOCUMENT -> this.documentId = sourceId;
            case EMAIL -> this.sourceMessageId = sourceId;
            case RESEARCH_ITEM -> this.researchItemId = sourceId;
            case CITATION -> this.chunkId = sourceId;
            case IMPACT_ASSESSMENT -> this.assessmentId = sourceId;
        }
        this.contentSha256 = contentSha256;
        this.title = title;
        this.publisher = publisher;
        this.publishedOn = publishedOn;
        this.location = location;
        this.excerpt = excerpt;
        this.note = note;
        this.attachedAt = attachedAt;
    }

    public void remove(String reason, Instant now) {
        this.removedAt = now;
        this.removedReason = reason;
    }

    /** The live reference of this evidence's kind; {@code null} once the source was deleted. */
    public UUID reference() {
        return switch (kind) {
            case DOCUMENT -> documentId;
            case EMAIL -> sourceMessageId;
            case RESEARCH_ITEM -> researchItemId;
            case CITATION -> chunkId;
            case IMPACT_ASSESSMENT -> assessmentId;
        };
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public EvidenceKind getKind() {
        return kind;
    }

    public EvidenceRole getRole() {
        return role;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getTitle() {
        return title;
    }

    public String getPublisher() {
        return publisher;
    }

    public LocalDate getPublishedOn() {
        return publishedOn;
    }

    public String getLocation() {
        return location;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getNote() {
        return note;
    }

    public Instant getAttachedAt() {
        return attachedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public String getRemovedReason() {
        return removedReason;
    }
}

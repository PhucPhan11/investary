package com.investary.research.extraction;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A piece of research information extracted deterministically from a source message.
 * Derived data: {@code content} is the original text and {@code sourceLocation} points back to it.
 */
@Entity
@Table(name = "research_items")
public class ResearchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_message_id", nullable = false, updatable = false)
    private UUID sourceMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, updatable = false)
    private ResearchItemType itemType;

    @Column(name = "ticker", updatable = false)
    private String ticker;

    @Column(name = "brokerage", updatable = false)
    private String brokerage;

    @Column(name = "published_at", updatable = false)
    private Instant publishedAt;

    @Column(name = "title", updatable = false)
    private String title;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    /** JSON object with values copied verbatim from the source; never computed or inferred. */
    @Column(name = "structured_data", columnDefinition = "jsonb", updatable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String structuredData;

    @Column(name = "extraction_method", nullable = false, updatable = false)
    private String extractionMethod;

    @Column(name = "source_location", nullable = false, updatable = false)
    private String sourceLocation;

    @Column(name = "language", updatable = false)
    private String language;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ResearchItem() {
        // for JPA
    }

    public ResearchItem(UUID sourceMessageId, ResearchItemType itemType, String ticker, String brokerage, Instant publishedAt,
                        String title, String content, String structuredData, String extractionMethod,
                        String sourceLocation, String language, Instant createdAt) {
        this.sourceMessageId = sourceMessageId;
        this.itemType = itemType;
        this.ticker = ticker;
        this.brokerage = brokerage;
        this.publishedAt = publishedAt;
        this.title = title;
        this.content = content;
        this.structuredData = structuredData;
        this.extractionMethod = extractionMethod;
        this.sourceLocation = sourceLocation;
        this.language = language;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceMessageId() {
        return sourceMessageId;
    }

    public ResearchItemType getItemType() {
        return itemType;
    }

    public String getTicker() {
        return ticker;
    }

    public String getBrokerage() {
        return brokerage;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getStructuredData() {
        return structuredData;
    }

    public String getExtractionMethod() {
        return extractionMethod;
    }

    public String getSourceLocation() {
        return sourceLocation;
    }

    public String getLanguage() {
        return language;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

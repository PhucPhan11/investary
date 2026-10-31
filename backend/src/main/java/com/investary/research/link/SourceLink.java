package com.investary.research.link;

import java.time.Instant;
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
 * A hyperlink found in a source message. The original URL is kept exactly as received;
 * {@code targetUrl} is the unwrapped destination used for classification and downloads.
 */
@Entity
@Table(name = "source_links")
public class SourceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_message_id", nullable = false, updatable = false)
    private UUID sourceMessageId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "original_url", nullable = false, updatable = false)
    private String originalUrl;

    @Column(name = "target_url", nullable = false, updatable = false)
    private String targetUrl;

    @Column(name = "host", nullable = false, updatable = false)
    private String host;

    @Column(name = "display_text", updatable = false)
    private String displayText;

    @Column(name = "section", updatable = false)
    private String section;

    @Column(name = "language", updatable = false)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private LinkCategory category;

    @Column(name = "classification_rule", nullable = false)
    private String classificationRule;

    @Column(name = "ticker_hint")
    private String tickerHint;

    /** Brokerage of the message the link came from (identified by sender), used when the link host is unknown. */
    @Column(name = "source_brokerage", updatable = false)
    private String sourceBrokerage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LinkStatus status;

    @Column(name = "discovered_at", nullable = false, updatable = false)
    private Instant discoveredAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "document_id")
    private UUID documentId;

    protected SourceLink() {
        // for JPA
    }

    public SourceLink(UUID sourceMessageId, int position, String originalUrl, String targetUrl, String host,
                      String displayText, String section, String language, String sourceBrokerage, Classification classification,
                      Instant discoveredAt) {
        this.sourceMessageId = sourceMessageId;
        this.position = position;
        this.originalUrl = originalUrl;
        this.targetUrl = targetUrl;
        this.host = host;
        this.displayText = displayText;
        this.section = section;
        this.language = language;
        this.sourceBrokerage = sourceBrokerage;
        this.category = classification.category();
        this.classificationRule = classification.rule();
        this.tickerHint = classification.tickerHint();
        this.status = classification.initialStatus();
        this.discoveredAt = discoveredAt;
    }

    public void markDownloading(Instant now) {
        this.status = LinkStatus.DOWNLOADING;
        this.lastAttemptAt = now;
        this.errorMessage = null;
    }

    public void markDownloaded(UUID documentId, Instant now) {
        this.status = LinkStatus.DOWNLOADED;
        this.documentId = documentId;
        this.lastAttemptAt = now;
        this.errorMessage = null;
    }

    public void recordAttemptError(String error, Instant now) {
        this.lastAttemptAt = now;
        this.errorMessage = error;
    }

    public void markFailed(String error, Instant now) {
        this.status = LinkStatus.FAILED;
        this.lastAttemptAt = now;
        this.errorMessage = error;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceMessageId() {
        return sourceMessageId;
    }

    public int getPosition() {
        return position;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public String getHost() {
        return host;
    }

    public String getDisplayText() {
        return displayText;
    }

    public String getSection() {
        return section;
    }

    public String getLanguage() {
        return language;
    }

    public LinkCategory getCategory() {
        return category;
    }

    public String getClassificationRule() {
        return classificationRule;
    }

    public String getTickerHint() {
        return tickerHint;
    }

    public String getSourceBrokerage() {
        return sourceBrokerage;
    }

    public LinkStatus getStatus() {
        return status;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}

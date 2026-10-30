package com.investary.document.domain;

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
 * Metadata of an uploaded investment research document. The binary content is kept in
 * {@link com.investary.document.storage.DocumentStorage} under {@link #storageKey}.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "sha256_checksum", nullable = false, updatable = false, length = 64)
    private String sha256Checksum;

    @Column(name = "brokerage_company")
    private String brokerageCompany;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type")
    private ReportType reportType;

    @Column(name = "ticker")
    private String ticker;

    @Column(name = "published_on")
    private LocalDate publishedOn;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Document() {
        // for JPA
    }

    public Document(String originalFilename, String storageKey, long fileSize, String mimeType,
                    String sha256Checksum, String brokerageCompany, ReportType reportType,
                    String ticker, LocalDate publishedOn, Instant uploadedAt) {
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.sha256Checksum = sha256Checksum;
        this.brokerageCompany = brokerageCompany;
        this.reportType = reportType;
        this.ticker = ticker;
        this.publishedOn = publishedOn;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getSha256Checksum() {
        return sha256Checksum;
    }

    public String getBrokerageCompany() {
        return brokerageCompany;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public String getTicker() {
        return ticker;
    }

    public LocalDate getPublishedOn() {
        return publishedOn;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}

package com.investary.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A brokerage source the user approved: which verified sender addresses or domains may be imported
 * from Gmail, optionally narrowed by subject keywords and a Gmail search query. Brokerages are data,
 * never hard-coded. {@code autoPdfDownload} is stored for a later milestone and not acted on yet.
 */
@Entity
@Table(name = "email_source_rules")
public class EmailSourceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "sender_addresses", nullable = false, columnDefinition = "text[]")
    private String[] senderAddresses = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "sender_domains", nullable = false, columnDefinition = "text[]")
    private String[] senderDomains = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "subject_keywords", nullable = false, columnDefinition = "text[]")
    private String[] subjectKeywords = new String[0];

    @Column(name = "gmail_query")
    private String gmailQuery;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "auto_import", nullable = false)
    private boolean autoImport;

    @Column(name = "auto_pdf_download", nullable = false)
    private boolean autoPdfDownload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmailSourceRule() {
        // for JPA
    }

    public EmailSourceRule(Instant now) {
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, List<String> senderAddresses, List<String> senderDomains, List<String> subjectKeywords,
                       String gmailQuery, boolean enabled, boolean autoImport, boolean autoPdfDownload, Instant now) {
        this.name = name;
        this.senderAddresses = senderAddresses.toArray(String[]::new);
        this.senderDomains = senderDomains.toArray(String[]::new);
        this.subjectKeywords = subjectKeywords.toArray(String[]::new);
        this.gmailQuery = gmailQuery;
        this.enabled = enabled;
        this.autoImport = autoImport;
        this.autoPdfDownload = autoPdfDownload;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getSenderAddresses() {
        return List.of(senderAddresses);
    }

    public List<String> getSenderDomains() {
        return List.of(senderDomains);
    }

    public List<String> getSubjectKeywords() {
        return List.of(subjectKeywords);
    }

    public String getGmailQuery() {
        return gmailQuery;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoImport() {
        return autoImport;
    }

    public boolean isAutoPdfDownload() {
        return autoPdfDownload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

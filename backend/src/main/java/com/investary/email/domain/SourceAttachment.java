package com.investary.email.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Metadata of a MIME attachment. The bytes stay inside the original EML; PDF attachments are also
 * imported into the document library ({@code documentId}).
 */
@Entity
@Table(name = "source_attachments")
public class SourceAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_message_id", nullable = false, updatable = false)
    private UUID sourceMessageId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "filename", updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256_checksum", nullable = false, updatable = false, length = 64)
    private String sha256Checksum;

    /** {@code ATTACHMENT} or {@code INLINE}. */
    @Column(name = "disposition", nullable = false, updatable = false)
    private String disposition;

    @Column(name = "content_id", updatable = false)
    private String contentId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "import_error")
    private String importError;

    protected SourceAttachment() {
        // for JPA
    }

    public SourceAttachment(UUID sourceMessageId, int position, String filename, String contentType, long sizeBytes,
                            String sha256Checksum, boolean inline, String contentId) {
        this.sourceMessageId = sourceMessageId;
        this.position = position;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256Checksum = sha256Checksum;
        this.disposition = inline ? "INLINE" : "ATTACHMENT";
        this.contentId = contentId;
    }

    public void linkDocument(UUID documentId) {
        this.documentId = documentId;
        this.importError = null;
    }

    public void recordImportError(String error) {
        this.importError = error;
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

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256Checksum() {
        return sha256Checksum;
    }

    public String getDisposition() {
        return disposition;
    }

    public String getContentId() {
        return contentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getImportError() {
        return importError;
    }
}

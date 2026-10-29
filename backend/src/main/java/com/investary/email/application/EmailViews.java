package com.investary.email.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.investary.email.domain.ParsingStatus;
import com.investary.email.domain.SourceAttachment;
import com.investary.research.application.LinkStatistics;

/**
 * Read models returned by the email API. They never expose storage keys or file paths.
 */
public final class EmailViews {

    private EmailViews() {
    }

    public record EmailSummary(
            UUID id,
            String subject,
            String senderName,
            String senderAddress,
            Instant sentAt,
            Instant importedAt,
            String brokerage,
            ParsingStatus parsingStatus,
            LinkStatistics statistics) {
    }

    /**
     * @param sameMessageIdAs other imported emails carrying the same Message-ID header but different file content
     */
    public record EmailDetail(
            UUID id,
            String subject,
            String senderName,
            String senderAddress,
            List<String> recipients,
            Instant sentAt,
            Instant importedAt,
            String messageId,
            String brokerage,
            List<String> languages,
            String originalFilename,
            long fileSize,
            String contentSha256,
            ParsingStatus parsingStatus,
            String parsingError,
            boolean hasHtml,
            String normalizedText,
            List<Attachment> attachments,
            LinkStatistics statistics,
            List<UUID> sameMessageIdAs) {
    }

    public record Attachment(UUID id, int position, String filename, String contentType, long size,
                             String disposition, UUID documentId, String importError) {

        static Attachment of(SourceAttachment a) {
            return new Attachment(a.getId(), a.getPosition(), a.getFilename(), a.getContentType(), a.getSizeBytes(),
                    a.getDisposition(), a.getDocumentId(), a.getImportError());
        }
    }

    public record Sender(String address, String name, long messages) {
    }

    /**
     * Stored, already parsed content of an email for internal consumers such as the knowledge base.
     * Not returned by the email API.
     */
    public record EmailContent(UUID id, String subject, String senderName, String senderAddress, Instant sentAt,
                               String brokerage, ParsingStatus parsingStatus, String htmlContent, String normalizedText) {
    }
}

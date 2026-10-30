package com.investary.document.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.investary.document.domain.Document;
import com.investary.document.domain.ReportType;

/**
 * Public view of a document. Deliberately excludes the internal storage key.
 */
public record DocumentResponse(
        UUID id,
        String originalFilename,
        long fileSize,
        String mimeType,
        String sha256Checksum,
        String brokerageCompany,
        ReportType reportType,
        String ticker,
        LocalDate publishedOn,
        Instant uploadedAt) {

    static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getFileSize(),
                document.getMimeType(),
                document.getSha256Checksum(),
                document.getBrokerageCompany(),
                document.getReportType(),
                document.getTicker(),
                document.getPublishedOn(),
                document.getUploadedAt());
    }
}

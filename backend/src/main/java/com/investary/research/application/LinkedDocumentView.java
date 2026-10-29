package com.investary.research.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.investary.document.domain.ReportType;
import com.investary.research.provenance.ProvenanceRepository.RelationshipType;

/**
 * A library document linked to a source message, with how it got there.
 */
public record LinkedDocumentView(
        UUID documentId,
        String originalFilename,
        long fileSize,
        String brokerageCompany,
        ReportType reportType,
        String ticker,
        LocalDate publishedOn,
        Instant uploadedAt,
        RelationshipType relationshipType,
        UUID sourceLinkId,
        UUID sourceAttachmentId,
        Instant linkedAt) {
}

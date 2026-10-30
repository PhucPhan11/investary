package com.investary.document.application;

import java.time.LocalDate;

import org.springframework.core.io.InputStreamSource;

import com.investary.document.domain.ReportType;

/**
 * @param content          re-readable source of the uploaded bytes (read for validation/hashing, then for storage)
 * @param originalFilename client-supplied name; sanitised before it is persisted
 */
public record UploadDocumentCommand(
        InputStreamSource content,
        String originalFilename,
        String brokerageCompany,
        ReportType reportType,
        String ticker,
        LocalDate publishedOn) {
}

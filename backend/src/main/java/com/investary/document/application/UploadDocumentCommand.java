package com.investary.document.application;

import java.time.LocalDate;

import org.springframework.core.io.InputStreamSource;

import com.investary.document.domain.ReportType;

/**
 * @param content          re-readable source of the uploaded bytes (read for validation/hashing, then for storage)
 * @param originalFilename client-supplied name; sanitised before it is persisted
 * @param origin           library upload, email attachment or downloaded report
 */
public record UploadDocumentCommand(
        InputStreamSource content,
        String originalFilename,
        String brokerageCompany,
        ReportType reportType,
        String ticker,
        LocalDate publishedOn,
        UploadOrigin origin) {

    public UploadDocumentCommand {
        origin = origin == null ? UploadOrigin.LIBRARY : origin;
    }

    /** A library upload. */
    public UploadDocumentCommand(InputStreamSource content, String originalFilename, String brokerageCompany,
                                 ReportType reportType, String ticker, LocalDate publishedOn) {
        this(content, originalFilename, brokerageCompany, reportType, ticker, publishedOn, UploadOrigin.LIBRARY);
    }
}

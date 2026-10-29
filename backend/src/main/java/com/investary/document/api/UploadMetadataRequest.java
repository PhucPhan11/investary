package com.investary.document.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.investary.document.domain.ReportType;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Optional metadata sent as multipart form fields alongside the PDF.
 */
public record UploadMetadataRequest(
        @Size(max = 200) String brokerageCompany,
        ReportType reportType,
        @Pattern(regexp = "^\\s*[A-Za-z0-9.\\-]{1,15}\\s*$", message = "must be 1-15 letters, digits, '.' or '-'")
        String ticker,
        @PastOrPresent @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishedOn) {
}

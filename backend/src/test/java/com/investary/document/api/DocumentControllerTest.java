package com.investary.document.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.document.TestPdfs;
import com.investary.document.application.DocumentDownload;
import com.investary.document.application.DocumentNotFoundException;
import com.investary.document.application.DocumentService;
import com.investary.document.application.DuplicateDocumentException;
import com.investary.document.application.InvalidDocumentException;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.document.domain.Document;
import com.investary.document.domain.ReportType;
import com.investary.document.storage.StorageException;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    private static final UUID ID = UUID.fromString("3f2b8c1e-0000-4000-8000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DocumentService documentService;

    @Test
    void uploadReturnsCreatedDocumentWithoutStorageKey() throws Exception {
        when(documentService.upload(any())).thenReturn(document());

        mvc.perform(multipart("/api/v1/documents")
                        .file(pdfPart())
                        .param("brokerageCompany", "SSI")
                        .param("reportType", "STOCK_ANALYSIS")
                        .param("ticker", "vcb")
                        .param("publishedOn", "2026-10-01"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/documents/" + ID)))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.reportType").value("STOCK_ANALYSIS"))
                .andExpect(jsonPath("$.storageKey").doesNotExist());

        ArgumentCaptor<UploadDocumentCommand> captor = ArgumentCaptor.forClass(UploadDocumentCommand.class);
        verify(documentService).upload(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().reportType()).isEqualTo(ReportType.STOCK_ANALYSIS);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().ticker()).isEqualTo("vcb");
    }

    @Test
    void uploadWithoutFileIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/v1/documents").param("ticker", "VCB"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(documentService, never()).upload(any());
    }

    @Test
    void uploadOfNonPdfIsBadRequest() throws Exception {
        when(documentService.upload(any())).thenThrow(new InvalidDocumentException("Only PDF documents are accepted"));

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "evil.pdf", "application/pdf", TestPdfs.notPdf())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"))
                .andExpect(jsonPath("$.detail").value("Only PDF documents are accepted"));
    }

    @Test
    void uploadWithInvalidMetadataReturnsFieldErrors() throws Exception {
        mvc.perform(multipart("/api/v1/documents")
                        .file(pdfPart())
                        .param("ticker", "not a ticker!")
                        .param("publishedOn", "2999-01-01")
                        .param("brokerageCompany", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.ticker").exists())
                .andExpect(jsonPath("$.errors.publishedOn").exists())
                .andExpect(jsonPath("$.errors.brokerageCompany").exists());
        verify(documentService, never()).upload(any());
    }

    @Test
    void uploadWithUnknownReportTypeIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/v1/documents")
                        .file(pdfPart())
                        .param("reportType", "CRYPTO_MOON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reportType").value("has an invalid value"));
        verify(documentService, never()).upload(any());
    }

    @Test
    void duplicateUploadIsConflictWithExistingId() throws Exception {
        when(documentService.upload(any())).thenThrow(new DuplicateDocumentException(ID));

        mvc.perform(multipart("/api/v1/documents").file(pdfPart()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"))
                .andExpect(jsonPath("$.existingDocumentId").value(ID.toString()));
    }

    @Test
    void storageFailureDoesNotLeakInternals() throws Exception {
        when(documentService.upload(any())).thenThrow(new StorageException("C:\\secret\\path failed"));

        mvc.perform(multipart("/api/v1/documents").file(pdfPart()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret"))));
    }

    @Test
    void listReturnsPageEnvelopeSortedByUploadDateByDefault() throws Exception {
        when(documentService.list(any())).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            return new PageImpl<>(List.of(document()), pageable, 1);
        });

        mvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentService).list(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "uploadedAt"));
    }

    @Test
    void listCapsPageSize() throws Exception {
        when(documentService.list(any())).thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(0), 0));

        mvc.perform(get("/api/v1/documents").param("size", "10000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentService).list(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPageSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void getReturnsDocument() throws Exception {
        when(documentService.get(ID)).thenReturn(document());

        mvc.perform(get("/api/v1/documents/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256Checksum").value("a".repeat(64)))
                .andExpect(jsonPath("$.uploadedAt").value("2026-10-29T08:00:00Z"));
    }

    @Test
    void getMissingDocumentIsNotFound() throws Exception {
        when(documentService.get(ID)).thenThrow(new DocumentNotFoundException(ID));

        mvc.perform(get("/api/v1/documents/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void malformedIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadStreamsPdfAsAttachment() throws Exception {
        byte[] bytes = TestPdfs.pdf("download");
        when(documentService.download(ID)).thenReturn(new DocumentDownload(document(), new ByteArrayResource(bytes)));

        mvc.perform(get("/api/v1/documents/{id}/download", ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("report.pdf")))
                .andExpect(header().longValue("Content-Length", bytes.length))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete("/api/v1/documents/{id}", ID))
                .andExpect(status().isNoContent());
        verify(documentService).delete(ID);
    }

    @Test
    void deleteMissingDocumentIsNotFound() throws Exception {
        doThrow(new DocumentNotFoundException(ID)).when(documentService).delete(ID);

        mvc.perform(delete("/api/v1/documents/{id}", ID))
                .andExpect(status().isNotFound());
    }

    private static MockMultipartFile pdfPart() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", TestPdfs.pdf("controller"));
    }

    private static Document document() {
        Document document = new Document("report.pdf", "11111111-1111-1111-1111-111111111111.pdf", 123,
                "application/pdf", "a".repeat(64), "SSI", ReportType.STOCK_ANALYSIS, "VCB", null,
                Instant.parse("2026-10-29T08:00:00Z"));
        ReflectionTestUtils.setField(document, "id", ID);
        return document;
    }
}

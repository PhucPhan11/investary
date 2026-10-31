package com.investary.email.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.email.application.EmailDeletionService;
import com.investary.email.application.EmailExceptions.DuplicateEmailException;
import com.investary.email.application.EmailExceptions.EmailNotFoundException;
import com.investary.email.application.EmailExceptions.InvalidEmailException;
import com.investary.email.application.EmailImportService;
import com.investary.email.application.EmailQueryService;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.email.domain.ParsingStatus;
import com.investary.research.application.DownloadNotAllowedException;
import com.investary.research.application.DownloadRequestService;
import com.investary.research.application.LinkStatistics;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.SourceLinkView;
import com.investary.research.link.LinkCategory;
import com.investary.research.link.LinkStatus;

@WebMvcTest(EmailController.class)
class EmailControllerTest {

    private static final UUID ID = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID LINK_ID = UUID.fromString("22222222-0000-4000-8000-000000000002");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EmailImportService importService;

    @MockitoBean
    EmailQueryService queryService;

    @MockitoBean
    EmailDeletionService deletionService;

    @MockitoBean
    ResearchQueryService research;

    @MockitoBean
    DownloadRequestService downloads;

    @Test
    void importReturnsCreatedDetailWithoutStorageDetails() throws Exception {
        when(importService.importEml(any())).thenReturn(ID);
        when(queryService.get(ID)).thenReturn(detail());

        mvc.perform(multipart("/api/v1/emails/import").file(eml()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/emails/" + ID)))
                .andExpect(jsonPath("$.subject").value("Cơ hội đầu tư | 28/10/2026"))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.statistics.researchLinks").value(4));
    }

    @Test
    void importWithoutFileIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/v1/emails/import"))
                .andExpect(status().isBadRequest());
        verify(importService, never()).importEml(any());
    }

    @Test
    void invalidEmailIsBadRequest() throws Exception {
        when(importService.importEml(any())).thenThrow(new InvalidEmailException("The file does not contain email headers"));

        mvc.perform(multipart("/api/v1/emails/import").file(eml()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
    }

    @Test
    void duplicateImportIsConflictWithExistingId() throws Exception {
        when(importService.importEml(any())).thenThrow(new DuplicateEmailException(ID));

        mvc.perform(multipart("/api/v1/emails/import").file(eml()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.existingEmailId").value(ID.toString()));
    }

    @Test
    void missingEmailIsNotFound() throws Exception {
        when(queryService.get(ID)).thenThrow(new EmailNotFoundException(ID));
        when(queryService.require(ID)).thenThrow(new EmailNotFoundException(ID));

        mvc.perform(get("/api/v1/emails/{id}", ID)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EMAIL_NOT_FOUND"));
        mvc.perform(get("/api/v1/emails/{id}/links", ID)).andExpect(status().isNotFound());
    }

    @Test
    void htmlPreviewIsServedWithStrictSecurityHeaders() throws Exception {
        when(queryService.safeHtml(ID)).thenReturn("<!DOCTYPE html><html><body>safe</body></html>");

        mvc.perform(get("/api/v1/emails/{id}/html", ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void downloadRequestIsAcceptedWithStatusLocation() throws Exception {
        when(downloads.request(ID, LINK_ID)).thenReturn(true);
        when(research.link(ID, LINK_ID)).thenReturn(link(LinkStatus.DOWNLOADING));

        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", ID, LINK_ID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/v1/emails/" + ID + "/links/" + LINK_ID)))
                .andExpect(jsonPath("$.status").value("DOWNLOADING"));
    }

    @Test
    void downloadOfAlreadyStoredReportReturnsOk() throws Exception {
        when(downloads.request(ID, LINK_ID)).thenReturn(false);
        when(research.link(ID, LINK_ID)).thenReturn(link(LinkStatus.DOWNLOADED));

        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", ID, LINK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWNLOADED"));
    }

    @Test
    void disallowedDownloadIsUnprocessable() throws Exception {
        when(downloads.request(eq(ID), eq(LINK_ID))).thenThrow(new DownloadNotAllowedException("Domain evil.example is not on the download allowlist"));

        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", ID, LINK_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOWNLOAD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.detail").value(containsString("allowlist")));
    }

    @Test
    void deleteReturnsNoContentAndMissingEmailIsNotFound() throws Exception {
        mvc.perform(delete("/api/v1/emails/{id}", ID)).andExpect(status().isNoContent());
        verify(deletionService).delete(ID);

        UUID missing = UUID.randomUUID();
        doThrow(new EmailNotFoundException(missing)).when(deletionService).delete(missing);
        mvc.perform(delete("/api/v1/emails/{id}", missing)).andExpect(status().isNotFound());
    }

    private static MockMultipartFile eml() {
        return new MockMultipartFile("file", "newsletter.eml", "message/rfc822", "From: a@example.com\n\nx".getBytes());
    }

    private static EmailDetail detail() {
        return new EmailDetail(ID, "Cơ hội đầu tư | 28/10/2026", "Research Desk", "research@tcbs.com.vn", List.of("investor@example.com"),
                Instant.parse("2026-10-28T12:14:40Z"), Instant.parse("2026-10-29T08:00:00Z"), "<id@example.com>", "TCBS",
                List.of("vi", "en"), "newsletter.eml", 1234, "a".repeat(64), ParsingStatus.PARSED, null, true, "text",
                List.of(), new LinkStatistics(12, 4, 0, 0, 0), List.of());
    }

    private static SourceLinkView link(LinkStatus status) {
        return new SourceLinkView(LINK_ID, 0, "IMP - Q3", "https://tracker/x", "https://www.tcbs.com.vn/a.pdf", "www.tcbs.com.vn",
                "1. Research", "en", LinkCategory.STOCK_RESEARCH, "stock.ticker", "IMP", status, Instant.now(), null, null, null, null);
    }
}

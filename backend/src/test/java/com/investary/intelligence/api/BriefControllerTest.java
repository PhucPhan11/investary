package com.investary.intelligence.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.common.web.PageResponse;
import com.investary.intelligence.application.BriefExceptions.BriefNotFoundException;
import com.investary.intelligence.application.BriefExceptions.BriefsDisabledException;
import com.investary.intelligence.application.BriefService;
import com.investary.intelligence.application.BriefViews.BriefJobView;
import com.investary.intelligence.domain.BriefJobStatus;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;

@WebMvcTest(BriefController.class)
class BriefControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BriefService briefs;

    private static BriefJobView job(UUID id, BriefTrigger trigger) {
        return new BriefJobView(id, trigger, BriefJobStatus.PENDING, null, null, Instant.parse("2026-10-31T01:00:00Z"),
                Instant.parse("2026-11-01T01:00:00Z"), "Asia/Ho_Chi_Minh", LocalDate.parse("2026-11-01"), false, null, 0, 3,
                Instant.parse("2026-11-01T02:00:00Z"), null, null, null, Instant.parse("2026-11-01T02:00:00Z"), null, null);
    }

    @Test
    void generateReturnsTheJobWithItsLocation() throws Exception {
        UUID portfolio = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(briefs.generate(portfolio, LocalDate.parse("2026-11-01"))).thenReturn(job(jobId, BriefTrigger.MANUAL));

        mvc.perform(post("/api/v1/briefs/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portfolioId\": \"" + portfolio + "\", \"date\": \"2026-11-01\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/briefs/jobs/" + jobId))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.zone", is("Asia/Ho_Chi_Minh")));
    }

    @Test
    void generateWithoutABodyIsAGeneralBriefForToday() throws Exception {
        when(briefs.generate(null, null)).thenReturn(job(UUID.randomUUID(), BriefTrigger.MANUAL));

        mvc.perform(post("/api/v1/briefs/generate")).andExpect(status().isAccepted());
        verify(briefs).generate(isNull(), isNull());
    }

    @Test
    void disabledBriefsAreAConflict() throws Exception {
        when(briefs.generate(null, null)).thenThrow(new BriefsDisabledException());

        mvc.perform(post("/api/v1/briefs/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("BRIEFS_DISABLED")));
    }

    @Test
    void unknownBriefIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(briefs.view(id)).thenThrow(new BriefNotFoundException(id));
        when(briefs.regenerate(id)).thenThrow(new BriefNotFoundException(id));

        mvc.perform(get("/api/v1/briefs/{id}", id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("BRIEF_NOT_FOUND")));
        mvc.perform(post("/api/v1/briefs/{id}/regenerate", id)).andExpect(status().isNotFound());
    }

    @Test
    void listPassesFiltersAndClampsThePageSize() throws Exception {
        UUID portfolio = UUID.randomUUID();
        when(briefs.list(eq(portfolio), eq(LocalDate.parse("2026-10-01")), eq(LocalDate.parse("2026-10-31")), eq(BriefStatus.SKIPPED), eq(0),
                eq(100))).thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        mvc.perform(get("/api/v1/briefs").param("portfolioId", portfolio.toString()).param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("status", "SKIPPED").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(100)));
    }

    @Test
    void regenerateIsAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(briefs.regenerate(id)).thenReturn(job(jobId, BriefTrigger.REGENERATION));

        mvc.perform(post("/api/v1/briefs/{id}/regenerate", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.triggerType", is("REGENERATION")));
    }
}

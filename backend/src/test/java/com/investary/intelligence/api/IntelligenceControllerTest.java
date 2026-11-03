package com.investary.intelligence.api;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.intelligence.application.ImpactAssessmentService;
import com.investary.intelligence.application.IntelligenceExceptions.PortfolioRequiredException;
import com.investary.intelligence.application.PortfolioAnalysisService;
import com.investary.intelligence.application.PortfolioAssistantService;
import com.investary.intelligence.application.ResearchMatcher.ResearchFilters;
import com.investary.portfolio.application.PortfolioExceptions.PortfolioNotFoundException;

@WebMvcTest(IntelligenceController.class)
class IntelligenceControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PortfolioAnalysisService analysis;

    @MockitoBean
    ImpactAssessmentService assessments;

    @MockitoBean
    PortfolioAssistantService assistant;

    @Test
    void askWithoutAPortfolioIsRejected() throws Exception {
        when(assistant.ask(eq("Danh mục của tôi?"), isNull(), any())).thenThrow(new PortfolioRequiredException(2));

        mvc.perform(post("/api/v1/intelligence/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"Danh mục của tôi?\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("PORTFOLIO_REQUIRED")))
                .andExpect(jsonPath("$.portfolioCount", is(2)));
    }

    @Test
    void validatesQuestionsAndFilters() throws Exception {
        mvc.perform(post("/api/v1/intelligence/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"?\", \"maxSources\": 99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("question")))
                .andExpect(jsonPath("$.errors", hasKey("maxSources")));
        mvc.perform(post("/api/v1/intelligence/portfolios/{id}/relevant-research", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": \"2026-10-31\", \"to\": \"2026-10-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("dateRangeValid")));
    }

    @Test
    void passesFiltersToAssessmentsAndAllowsAnEmptyBody() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/intelligence/portfolios/{id}/impact-assessments", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brokerage\": \" SSI \", \"from\": \"2026-10-01\"}"))
                .andExpect(status().isCreated());
        verify(assessments).create(id, new ResearchFilters("SSI", LocalDate.parse("2026-10-01"), null));

        mvc.perform(post("/api/v1/intelligence/portfolios/{id}/relevant-research", id)).andExpect(status().isOk());
        verify(analysis).relevantResearch(id, ResearchFilters.NONE);
    }

    @Test
    void unknownPortfolioIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(analysis.analyze(id)).thenThrow(new PortfolioNotFoundException(id));

        mvc.perform(post("/api/v1/intelligence/portfolios/{id}/analyze", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("PORTFOLIO_NOT_FOUND")));
    }
}

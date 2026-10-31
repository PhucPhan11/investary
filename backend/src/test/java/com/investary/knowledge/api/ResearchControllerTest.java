package com.investary.knowledge.api;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.AiExceptions.AiTimeoutException;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.AssistantAnswer;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.ResearchAssistantService;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.QueryAnalysis;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchResult;

@WebMvcTest(ResearchController.class)
class ResearchControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HybridSearchService search;

    @MockitoBean
    ResearchAssistantService assistant;

    @Test
    void askPassesFiltersAndReturnsTheAnswer() throws Exception {
        when(assistant.ask(anyString(), any(), any())).thenReturn(new AssistantAnswer("Không đủ bằng chứng.",
                EvidenceStatus.INSUFFICIENT, List.of(), List.of(), List.of("SSI"), List.of(), null, null, AssistantAnswer.DISCLAIMER));

        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON).content("""
                        {"question": "  SSI nói gì về SCS?  ", "ticker": "scs", "brokerage": "SSI", "sourceType": "EMAIL",
                         "from": "2026-10-01", "to": "2026-10-31", "maxResults": 5}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceStatus", is("INSUFFICIENT")))
                .andExpect(jsonPath("$.coverageGaps[0]", is("SSI")));

        verify(assistant).ask(eq("SSI nói gì về SCS?"),
                eq(new SearchFilters("SCS", "SSI", KnowledgeSourceType.EMAIL, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))),
                eq(5));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.errors", hasKey("question")));
        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"SCS?\", \"ticker\": \"S C S;--\", \"maxResults\": 50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("ticker")))
                .andExpect(jsonPath("$.errors", hasKey("maxResults")));
        mvc.perform(post("/api/v1/research/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"SCS\", \"from\": \"2026-10-31\", \"to\": \"2026-10-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("dateRangeValid")));
        mvc.perform(post("/api/v1/research/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"SCS\", \"sourceType\": \"WEBSITE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingProviderAndTimeoutsAreProblemResponses() throws Exception {
        doThrow(new AiNotConfiguredException("chat")).when(assistant).ask(anyString(), any(), any());
        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"SCS?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("AI_NOT_CONFIGURED")));

        doThrow(new AiTimeoutException(120)).when(assistant).ask(anyString(), any(), any());
        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"SCS?\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code", is("AI_TIMEOUT")));
    }

    @Test
    void searchReportsModeAndDetectedEntities() throws Exception {
        when(search.search(anyString(), any(), any())).thenReturn(new SearchResult(List.of(), SearchResult.Mode.LEXICAL_ONLY,
                QueryAnalysis.of("So sánh TCBS và SSI về FPT", List.of("TCBS", "SSI")), List.of("SSI")));

        mvc.perform(post("/api/v1/research/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"So sánh TCBS và SSI về FPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("LEXICAL_ONLY")))
                .andExpect(jsonPath("$.detectedTickers[0]", is("FPT")))
                .andExpect(jsonPath("$.detectedBrokerages[1]", is("SSI")))
                .andExpect(jsonPath("$.coverageGaps[0]", is("SSI")))
                .andExpect(jsonPath("$.results.length()", is(0)));
    }
}

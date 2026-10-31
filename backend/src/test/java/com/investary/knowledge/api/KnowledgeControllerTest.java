package com.investary.knowledge.api;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.email.application.EmailExceptions.EmailNotFoundException;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.index.KnowledgeIndexService;
import com.investary.knowledge.index.KnowledgeJobNotFoundException;
import com.investary.knowledge.index.ReindexScope;

@WebMvcTest(KnowledgeController.class)
class KnowledgeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KnowledgeIndexService service;

    @Test
    void untrackedSourceIsReportedAsNotIndexed() throws Exception {
        UUID email = UUID.randomUUID();
        when(service.forSource(KnowledgeSourceType.EMAIL, email)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/knowledge/emails/{id}", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_INDEXED")))
                .andExpect(jsonPath("$.id", nullValue()))
                .andExpect(jsonPath("$.sourceId", is(email.toString())));
    }

    @Test
    void unknownSourcesAndJobsAreNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.request(KnowledgeSourceType.EMAIL, id)).thenThrow(new EmailNotFoundException(id));
        when(service.job(id)).thenThrow(new KnowledgeJobNotFoundException(id));

        mvc.perform(post("/api/v1/knowledge/index/emails/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("EMAIL_NOT_FOUND")));
        mvc.perform(get("/api/v1/knowledge/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("KNOWLEDGE_JOB_NOT_FOUND")));
    }

    @Test
    void reindexRequiresAKnownScope() throws Exception {
        when(service.reindex(ReindexScope.FAILED)).thenReturn(2);

        mvc.perform(post("/api/v1/knowledge/reindex").contentType(MediaType.APPLICATION_JSON).content("{\"scope\": \"FAILED\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued", is(2)));
        mvc.perform(post("/api/v1/knowledge/reindex").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    void invalidIdIsRejectedBeforeReachingTheService() throws Exception {
        mvc.perform(post("/api/v1/knowledge/index/documents/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

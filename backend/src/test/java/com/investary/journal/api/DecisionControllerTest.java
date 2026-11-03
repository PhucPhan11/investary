package com.investary.journal.api;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.journal.application.DecisionQueryService;
import com.investary.journal.application.DecisionService;
import com.investary.journal.application.JournalExceptions.DecisionConflictException;
import com.investary.journal.application.JournalExceptions.FieldLockedException;

@WebMvcTest(DecisionController.class)
class DecisionControllerTest {

    private static final UUID DECISION = new UUID(3, 3);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DecisionQueryService queries;

    @MockitoBean
    DecisionService decisions;

    @Test
    void rejectsIncompleteRequests() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"decisionType": null, "title": " ", "intendedQuantity": 0, "intendedAllocationPct": 101}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.errors", hasKey("decisionType")))
                .andExpect(jsonPath("$.errors", hasKey("title")))
                .andExpect(jsonPath("$.errors", hasKey("intendedQuantity")))
                .andExpect(jsonPath("$.errors", hasKey("intendedAllocationPct")));
        mvc.perform(post("/api/v1/decisions/{id}/finalize", DECISION).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("expectedVersion")));
        mvc.perform(post("/api/v1/decisions/{id}/evidence", DECISION).contentType(MediaType.APPLICATION_JSON).content("""
                        {"kind": "DOCUMENT", "role": "SUPPORTING", "expectedVersion": 1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("sourceId")));
        verifyNoInteractions(decisions);
    }

    @Test
    void conflictsAreProblemDetailsWithTheirFields() throws Exception {
        when(decisions.finalizeDecision(eq(DECISION), any())).thenThrow(new DecisionConflictException(4));
        mvc.perform(post("/api/v1/decisions/{id}/finalize", DECISION).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DECISION_CONFLICT")))
                .andExpect(jsonPath("$.currentVersion", is(4)));

        when(decisions.update(eq(DECISION), any())).thenThrow(new FieldLockedException(List.of("decisionType")));
        mvc.perform(put("/api/v1/decisions/{id}", DECISION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionType\": \"SELL\", \"title\": \"Bán\", \"expectedVersion\": 2, \"amendmentReason\": \"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DECISION_FIELD_LOCKED")))
                .andExpect(jsonPath("$.fields[0]", is("decisionType")));
    }
}

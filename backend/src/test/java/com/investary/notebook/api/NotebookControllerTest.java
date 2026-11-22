package com.investary.notebook.api;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.notebook.application.NoteService;
import com.investary.notebook.application.NotebookChatService;
import com.investary.notebook.application.NotebookExceptions.NoteLimitReachedException;
import com.investary.notebook.application.NotebookExceptions.NotebookConflictException;
import com.investary.notebook.application.NotebookService;
import com.investary.notebook.application.NotebookViews.NoteView;
import com.investary.notebook.domain.NoteKind;
import com.investary.notebook.domain.NoteStatus;

@WebMvcTest(NotebookController.class)
class NotebookControllerTest {

    private static final UUID NOTEBOOK = new UUID(4, 4);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NotebookService notebooks;

    @MockitoBean
    NotebookChatService chat;

    @MockitoBean
    NoteService notes;

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/api/v1/notebooks").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.errors", hasKey("title")));
        mvc.perform(post("/api/v1/notebooks/{id}/ask", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("question")));
        mvc.perform(post("/api/v1/notebooks/{id}/sources", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("anySource")));
        mvc.perform(post("/api/v1/notebooks/{id}/sources", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("""
                        {"filter": {"ticker": "not a ticker!"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("filter.ticker")));
        mvc.perform(post("/api/v1/notebooks/{id}/sources", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("""
                        {"filter": {}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("filter.anyCriterion")));
        mvc.perform(post("/api/v1/notebooks/{id}/notes", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("kind")));
        mvc.perform(patch("/api/v1/notebooks/{id}", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("expectedVersion")));
        verifyNoInteractions(notebooks, chat, notes);
    }

    @Test
    void newNotesAreAcceptedAndReusedNotesAreReturned() throws Exception {
        when(notes.request(eq(NOTEBOOK), eq(NoteKind.SUMMARY), any())).thenReturn(note(false));
        mvc.perform(post("/api/v1/notebooks/{id}/notes", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"SUMMARY\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("PENDING")));

        when(notes.request(eq(NOTEBOOK), eq(NoteKind.SUMMARY), any())).thenReturn(note(true));
        mvc.perform(post("/api/v1/notebooks/{id}/notes", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"SUMMARY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused", is(true)));
    }

    @Test
    void mapsExpectedFailures() throws Exception {
        when(notes.request(eq(NOTEBOOK), eq(NoteKind.FAQ), any())).thenThrow(new NoteLimitReachedException(30));
        mvc.perform(post("/api/v1/notebooks/{id}/notes", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"FAQ\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("NOTE_LIMIT_REACHED")));

        when(notebooks.update(eq(NOTEBOOK), any(), any(), eq(2))).thenThrow(new NotebookConflictException(3));
        mvc.perform(patch("/api/v1/notebooks/{id}", NOTEBOOK).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "Mới", "expectedVersion": 2}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("NOTEBOOK_CONFLICT")))
                .andExpect(jsonPath("$.currentVersion", is(3)));
    }

    private static NoteView note(boolean reused) {
        return new NoteView(new UUID(5, 5), NoteKind.SUMMARY, null, reused ? NoteStatus.DONE : NoteStatus.PENDING, null, List.of(), null,
                null, null, Instant.parse("2026-11-22T01:00:00Z"), null, List.of(), reused);
    }
}

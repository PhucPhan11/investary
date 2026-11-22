package com.investary.notebook.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.search.SearchFilters;
import com.investary.notebook.application.NoteService;
import com.investary.notebook.application.NotebookChatService;
import com.investary.notebook.application.NotebookService;
import com.investary.notebook.application.NotebookViews.AskResult;
import com.investary.notebook.application.NotebookViews.MessageView;
import com.investary.notebook.application.NotebookViews.NoteView;
import com.investary.notebook.application.NotebookViews.NotebookDetail;
import com.investary.notebook.application.NotebookViews.NotebookSummary;
import com.investary.notebook.domain.NoteKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Research notebooks: a picked set of documents and emails, chat over only those sources, and generated notes with
 * validated citations. Single-user, local use only: these endpoints have no authentication and must not be exposed
 * publicly.
 */
@RestController
@RequestMapping("/api/v1/notebooks")
public class NotebookController {

    private final NotebookService notebooks;
    private final NotebookChatService chat;
    private final NoteService notes;

    public NotebookController(NotebookService notebooks, NotebookChatService chat, NoteService notes) {
        this.notebooks = notebooks;
        this.chat = chat;
        this.notes = notes;
    }

    @GetMapping
    public List<NotebookSummary> list() {
        return notebooks.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotebookDetail create(@Valid @RequestBody CreateRequest request) {
        return notebooks.create(request.title(), request.description());
    }

    @GetMapping("/{id}")
    public NotebookDetail get(@PathVariable UUID id) {
        return notebooks.detail(id);
    }

    @PatchMapping("/{id}")
    public NotebookDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return notebooks.update(id, request.title(), request.description(), request.expectedVersion());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        notebooks.delete(id);
    }

    @PostMapping("/{id}/sources")
    public NotebookDetail addSources(@PathVariable UUID id, @Valid @RequestBody AddSourcesRequest request) {
        return notebooks.addSources(id, request.documentIdsOrEmpty(), request.messageIdsOrEmpty(),
                request.filter() == null ? null : request.filter().filters());
    }

    @DeleteMapping("/{id}/sources/{sourceId}")
    public NotebookDetail removeSource(@PathVariable UUID id, @PathVariable UUID sourceId) {
        return notebooks.removeSource(id, sourceId);
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id) {
        return chat.messages(id);
    }

    @PostMapping("/{id}/ask")
    public AskResult ask(@PathVariable UUID id, @Valid @RequestBody AskRequest request) {
        return chat.ask(id, request.question());
    }

    @DeleteMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMessages(@PathVariable UUID id) {
        chat.clear(id);
    }

    @GetMapping("/{id}/notes")
    public List<NoteView> notes(@PathVariable UUID id) {
        return notes.list(id);
    }

    /** 202 while the note is generated in the background; 200 when an identical earlier note was returned. */
    @PostMapping("/{id}/notes")
    public ResponseEntity<NoteView> requestNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        NoteView note = notes.request(id, request.kind(), request.ticker());
        return ResponseEntity.status(note.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(note);
    }

    @GetMapping("/{id}/notes/{noteId}")
    public NoteView note(@PathVariable UUID id, @PathVariable UUID noteId) {
        return notes.get(id, noteId);
    }

    @PostMapping("/{id}/notes/{noteId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NoteView retryNote(@PathVariable UUID id, @PathVariable UUID noteId) {
        return notes.retry(id, noteId);
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description,
                                @NotNull @PositiveOrZero Integer expectedVersion) {
    }

    public record AddSourcesRequest(@Size(max = 1000) Set<UUID> documentIds, @Size(max = 1000) Set<UUID> messageIds,
                                    @Valid SourceFilter filter) {

        Set<UUID> documentIdsOrEmpty() {
            return documentIds == null ? Set.of() : documentIds;
        }

        Set<UUID> messageIdsOrEmpty() {
            return messageIds == null ? Set.of() : messageIds;
        }

        @AssertTrue(message = "give documentIds, messageIds or a filter")
        public boolean isAnySource() {
            return !documentIdsOrEmpty().isEmpty() || !messageIdsOrEmpty().isEmpty() || filter != null;
        }
    }

    /** Resolved once to the matching indexed sources; at least one criterion is required. */
    public record SourceFilter(
            @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "must be a stock ticker") String ticker,
            @Size(max = 100) String brokerage,
            KnowledgeSourceType sourceType,
            LocalDate from,
            LocalDate to) {

        SearchFilters filters() {
            return new SearchFilters(ticker, brokerage, sourceType, from, to);
        }

        @AssertTrue(message = "from must not be after to")
        public boolean isDateRangeValid() {
            return from == null || to == null || !from.isAfter(to);
        }

        @AssertTrue(message = "give at least one filter criterion")
        public boolean isAnyCriterion() {
            return (ticker != null && !ticker.isBlank()) || (brokerage != null && !brokerage.isBlank()) || sourceType != null
                    || from != null || to != null;
        }
    }

    public record AskRequest(@NotBlank @Size(min = 3, max = 1000) String question) {
    }

    public record NoteRequest(@NotNull NoteKind kind, @Size(max = 15) String ticker) {
    }
}

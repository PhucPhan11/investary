package com.investary.notebook.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.notebook.domain.MessageRole;
import com.investary.notebook.domain.NoteKind;
import com.investary.notebook.domain.NoteStatus;

import tools.jackson.databind.JsonNode;

/**
 * API views of notebooks. No storage keys or file paths; source ids only.
 */
public final class NotebookViews {

    private NotebookViews() {
    }

    public record NotebookSummary(UUID id, String title, String description, int version, int sourceCount, int questionCount,
                                  int noteCount, Instant createdAt, Instant updatedAt) {
    }

    /**
     * @param tickers tickers named in the searchable sources, for the per-ticker notes
     */
    public record NotebookDetail(UUID id, String title, String description, int version, Instant createdAt, Instant updatedAt,
                                 List<SourceView> sources, List<String> tickers, int searchableSources) {
    }

    /**
     * @param indexState INDEXED, NOT_TRACKED, PENDING, PROCESSING, FAILED, SKIPPED or OUTDATED; only INDEXED sources are used
     */
    public record SourceView(KnowledgeSourceType sourceType, UUID sourceId, String title, String brokerage, LocalDate publishedOn,
                             String dateBasis, Instant sentAt, Instant importedAt, List<String> tickers, String indexState,
                             Instant addedAt) {
    }

    /**
     * @param sourceId  document or email id; {@code null} when the source was deleted after the citation was written
     * @param available false when the source was deleted; title, location and excerpt are copies kept from the time of writing
     */
    public record CitationView(String label, KnowledgeSourceType sourceType, UUID sourceId, boolean available, UUID chunkId, String title,
                               String publisher, LocalDate publishedOn, String dateBasis, String location, String excerpt) {
    }

    public record MessageView(UUID id, MessageRole role, String content, String evidenceStatus, List<String> warnings, String model,
                              Instant createdAt, List<CitationView> citations) {
    }

    public record AskResult(MessageView question, MessageView answer, List<String> coverageGaps, String disclaimer) {
    }

    /**
     * @param content     structured note content (shape per kind, see {@code NoteContent}); {@code null} until DONE
     * @param limitations what the note could not cover (omitted sources, dropped items)
     * @param reused      true when an identical earlier note was returned instead of generating a new one
     */
    public record NoteView(UUID id, NoteKind kind, String ticker, NoteStatus status, JsonNode content, List<String> limitations,
                           String evidenceStatus, String model, String errorSummary, Instant createdAt, Instant completedAt,
                           List<CitationView> citations, boolean reused) {
    }
}

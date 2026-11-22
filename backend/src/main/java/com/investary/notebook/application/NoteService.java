package com.investary.notebook.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.common.util.Hashing;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceState;
import com.investary.notebook.application.NotebookExceptions.NoteLimitReachedException;
import com.investary.notebook.application.NotebookExceptions.NoteNotFoundException;
import com.investary.notebook.application.NotebookExceptions.NoteNotRetryableException;
import com.investary.notebook.application.NotebookExceptions.NotebookEmptyException;
import com.investary.notebook.application.NotebookViews.NoteView;
import com.investary.notebook.domain.NoteKind;
import com.investary.notebook.persistence.NoteQueue;
import com.investary.notebook.persistence.NoteQueue.NoteRow;
import com.investary.notebook.persistence.NotebookStore;
import com.investary.notebook.persistence.NotebookStore.CitationRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * Requests and reads generated notes. A request with the same inputs as an earlier DONE note (same sources and their
 * indexed text, kind, ticker, prompt version, model and limits) returns that note without a model call; a request
 * while one of the same kind is still running returns the running one.
 */
@Service
public class NoteService {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9]{1,15}");

    private final NotebookService notebooks;
    private final NoteQueue queue;
    private final NotebookStore store;
    private final NotebookProperties properties;
    private final IndexVersion indexVersion;
    private final KnowledgeProperties knowledge;
    private final JsonMapper json;
    private final Clock clock;

    public NoteService(NotebookService notebooks, NoteQueue queue, NotebookStore store, NotebookProperties properties,
                       IndexVersion indexVersion, KnowledgeProperties knowledge, JsonMapper json, Clock clock) {
        this.notebooks = notebooks;
        this.queue = queue;
        this.store = store;
        this.properties = properties;
        this.indexVersion = indexVersion;
        this.knowledge = knowledge;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public NoteView request(UUID notebookId, NoteKind kind, String rawTicker) {
        notebooks.require(notebookId);
        String ticker = ticker(kind, rawTicker);
        List<SourceState> searchable = notebooks.scope(notebookId).searchable();
        if (searchable.isEmpty()) {
            throw new NotebookEmptyException();
        }
        String fingerprint = fingerprint(kind, ticker, searchable);
        var reused = queue.done(notebookId, kind, ticker, fingerprint);
        if (reused.isPresent()) {
            log.info("Notebook note unchanged notebook={} kind={} note={}", notebookId, kind, reused.get().id());
            return view(reused.get(), true);
        }
        var active = queue.active(notebookId, kind, ticker);
        if (active.isPresent()) {
            return view(active.get(), false);
        }
        int today = queue.countCreatedSince(LocalDate.now(clock.withZone(ZONE)).atStartOfDay(ZONE).toInstant());
        if (today >= properties.dailyNoteLimit()) {
            throw new NoteLimitReachedException(properties.dailyNoteLimit());
        }
        UUID id = queue.insert(notebookId, kind, ticker, fingerprint, properties.maxAttempts())
                .orElseGet(() -> queue.active(notebookId, kind, ticker).map(NoteRow::id).orElseThrow());
        log.info("Notebook note requested notebook={} kind={} note={} sources={}", notebookId, kind, id, searchable.size());
        return get(notebookId, id);
    }

    @Transactional(readOnly = true)
    public List<NoteView> list(UUID notebookId) {
        notebooks.require(notebookId);
        List<NoteRow> rows = queue.list(notebookId);
        Map<UUID, List<CitationRow>> citations = store.noteCitations(rows.stream().map(NoteRow::id).toList());
        return rows.stream().map(row -> view(row, citations.getOrDefault(row.id(), List.of()), false)).toList();
    }

    @Transactional(readOnly = true)
    public NoteView get(UUID notebookId, UUID noteId) {
        return queue.find(notebookId, noteId).map(row -> view(row, false)).orElseThrow(() -> new NoteNotFoundException(noteId));
    }

    @Transactional
    public NoteView retry(UUID notebookId, UUID noteId) {
        NoteRow note = queue.find(notebookId, noteId).orElseThrow(() -> new NoteNotFoundException(noteId));
        if (!queue.resetFailed(notebookId, note.id())) {
            throw new NoteNotRetryableException();
        }
        return get(notebookId, noteId);
    }

    /** Upper-case ticker for per-ticker kinds (required); null for the others. */
    static String ticker(NoteKind kind, String raw) {
        if (!kind.perTicker()) {
            return null;
        }
        String ticker = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!TICKER.matcher(ticker).matches()) {
            throw new NotebookExceptions.NoteTickerRequiredException(kind.name());
        }
        return ticker;
    }

    String fingerprint(NoteKind kind, String ticker, List<SourceState> searchable) {
        StringBuilder canonical = new StringBuilder()
                .append(NotePrompts.VERSION).append('|').append(kind).append('|').append(ticker == null ? "" : ticker)
                .append('|').append(indexVersion.current()).append('|').append(knowledge.assistant().modelLabel())
                .append('|').append(properties.noteSources()).append('|').append(properties.chunksPerSource())
                .append('|').append(properties.maxContextChars());
        searchable.stream().sorted(Comparator.comparing(SourceState::knowledgeSourceId))
                .forEach(s -> canonical.append('|').append(s.knowledgeSourceId()).append(':').append(s.contentSha256()));
        return Hashing.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private NoteView view(NoteRow row, boolean reused) {
        return view(row, store.noteCitations(List.of(row.id())).getOrDefault(row.id(), List.of()), reused);
    }

    private NoteView view(NoteRow row, List<CitationRow> citations, boolean reused) {
        return new NoteView(row.id(), row.kind(), row.ticker(), row.status(), NotebookJson.tree(json, row.contentJson()),
                NotebookJson.strings(json, row.limitationsJson()), row.evidenceStatus(), row.model(), row.errorSummary(), row.createdAt(),
                row.completedAt(), citations.stream().map(NotebookChatService::view).toList(), reused);
    }
}

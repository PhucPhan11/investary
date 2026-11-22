package com.investary.notebook.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.search.ResearchAvailabilityService;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceRef;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceState;
import com.investary.knowledge.search.SearchFilters;
import com.investary.notebook.application.NotebookExceptions.NotebookConflictException;
import com.investary.notebook.application.NotebookExceptions.NotebookNotFoundException;
import com.investary.notebook.application.NotebookExceptions.NotebookSourceLimitException;
import com.investary.notebook.application.NotebookViews.NotebookDetail;
import com.investary.notebook.application.NotebookViews.NotebookSummary;
import com.investary.notebook.application.NotebookViews.SourceView;
import com.investary.notebook.persistence.NotebookStore;
import com.investary.notebook.persistence.NotebookStore.NotebookRow;
import com.investary.notebook.persistence.NotebookStore.SourceRow;

/**
 * Notebooks and their sources. A notebook holds a fixed list of library documents and emails: a filter is resolved
 * to concrete sources once, when they are added, so the notebook does not change as new research arrives.
 */
@Service
public class NotebookService {

    private static final Logger log = LoggerFactory.getLogger(NotebookService.class);
    private static final int MAX_TICKERS = 50;

    private final NotebookStore store;
    private final ResearchAvailabilityService availability;
    private final NotebookProperties properties;

    public NotebookService(NotebookStore store, ResearchAvailabilityService availability, NotebookProperties properties) {
        this.store = store;
        this.availability = availability;
        this.properties = properties;
    }

    @Transactional
    public NotebookDetail create(String title, String description) {
        UUID id = UUID.randomUUID();
        store.insert(id, title.strip(), blankToNull(description));
        log.info("Notebook created id={}", id);
        return detail(id);
    }

    @Transactional
    public NotebookDetail update(UUID id, String title, String description, int expectedVersion) {
        if (!store.update(id, title.strip(), blankToNull(description), expectedVersion)) {
            NotebookRow current = store.find(id).orElseThrow(() -> new NotebookNotFoundException(id));
            throw new NotebookConflictException(current.version());
        }
        return detail(id);
    }

    @Transactional
    public void delete(UUID id) {
        if (!store.delete(id)) {
            throw new NotebookNotFoundException(id);
        }
        log.info("Notebook deleted id={}", id);
    }

    @Transactional(readOnly = true)
    public List<NotebookSummary> list() {
        return store.list().stream().map(NotebookService::summary).toList();
    }

    @Transactional(readOnly = true)
    public NotebookDetail detail(UUID id) {
        NotebookRow notebook = store.find(id).orElseThrow(() -> new NotebookNotFoundException(id));
        Scope scope = scope(id);
        List<SourceView> sources = scope.states().stream()
                .map(state -> new SourceView(state.sourceType(), state.sourceId(), state.title(), state.brokerage(), state.publishedOn(),
                        state.dateBasis(), state.sentAt(), state.importedAt(), state.tickers(), state.state(),
                        scope.addedAt().get(state.sourceId())))
                .toList();
        Set<String> tickers = new TreeSet<>();
        for (SourceState state : scope.searchable()) {
            tickers.addAll(state.tickers());
            if (state.sourceTicker() != null) {
                tickers.add(state.sourceTicker());
            }
        }
        return new NotebookDetail(notebook.id(), notebook.title(), notebook.description(), notebook.version(), notebook.createdAt(),
                notebook.updatedAt(), sources, tickers.stream().limit(MAX_TICKERS).toList(), scope.searchable().size());
    }

    /**
     * Adds explicit sources, and the sources matching {@code filter} (newest first, up to the notebook's free capacity).
     *
     * @throws NotebookSourceLimitException when the explicit sources alone would exceed the notebook's capacity
     */
    @Transactional
    public NotebookDetail addSources(UUID id, Set<UUID> documentIds, Set<UUID> messageIds, SearchFilters filter) {
        store.lock(id).orElseThrow(() -> new NotebookNotFoundException(id));
        List<SourceRow> existing = store.sources(id);
        Set<UUID> present = new java.util.HashSet<>();
        existing.forEach(row -> present.add(row.documentId() != null ? row.documentId() : row.messageId()));

        Set<UUID> documents = new LinkedHashSet<>(documentIds);
        Set<UUID> messages = new LinkedHashSet<>(messageIds);
        documents.removeAll(present);
        messages.removeAll(present);
        int free = properties.maxNotebookSources() - existing.size();
        if (documents.size() + messages.size() > free) {
            throw new NotebookSourceLimitException(properties.maxNotebookSources());
        }
        if (filter != null) {
            int room = free - documents.size() - messages.size();
            for (SourceRef ref : availability.matching(filter, properties.maxNotebookSources())) {
                if (room <= 0) {
                    break;
                }
                if (present.contains(ref.sourceId()) || documents.contains(ref.sourceId()) || messages.contains(ref.sourceId())) {
                    continue;
                }
                (ref.sourceType() == KnowledgeSourceType.DOCUMENT ? documents : messages).add(ref.sourceId());
                room--;
            }
        }
        int added = store.addSources(id, documents, messages);
        if (added > 0) {
            store.touch(id);
        }
        log.info("Notebook sources added id={} requested={} added={}", id, documents.size() + messages.size(), added);
        return detail(id);
    }

    @Transactional
    public NotebookDetail removeSource(UUID id, UUID sourceId) {
        store.lock(id).orElseThrow(() -> new NotebookNotFoundException(id));
        if (store.removeSource(id, sourceId)) {
            store.touch(id);
        }
        return detail(id);
    }

    /** The notebook's sources with their index state; for chat and notes. */
    @Transactional(readOnly = true)
    public Scope scope(UUID id) {
        List<SourceRow> rows = store.sources(id);
        Set<UUID> documents = new LinkedHashSet<>();
        Set<UUID> messages = new LinkedHashSet<>();
        Map<UUID, java.time.Instant> addedAt = new HashMap<>();
        for (SourceRow row : rows) {
            if (row.documentId() != null) {
                documents.add(row.documentId());
                addedAt.put(row.documentId(), row.addedAt());
            } else {
                messages.add(row.messageId());
                addedAt.put(row.messageId(), row.addedAt());
            }
        }
        List<SourceState> states = availability.describe(documents, messages);
        return new Scope(Set.copyOf(documents), Set.copyOf(messages), states, Map.copyOf(addedAt));
    }

    /** Fails with 404 when the notebook does not exist. */
    @Transactional(readOnly = true)
    public NotebookRow require(UUID id) {
        return store.find(id).orElseThrow(() -> new NotebookNotFoundException(id));
    }

    private static NotebookSummary summary(NotebookRow row) {
        return new NotebookSummary(row.id(), row.title(), row.description(), row.version(), row.sourceCount(), row.questionCount(),
                row.noteCount(), row.createdAt(), row.updatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * @param states every source of the notebook, newest first, with its index state
     */
    public record Scope(Set<UUID> documentIds, Set<UUID> messageIds, List<SourceState> states, Map<UUID, java.time.Instant> addedAt) {

        public List<SourceState> searchable() {
            List<SourceState> result = new ArrayList<>();
            states.forEach(state -> {
                if (state.searchable()) {
                    result.add(state);
                }
            });
            return result;
        }

        public SearchFilters filters() {
            return SearchFilters.NONE.scopedTo(documentIds, messageIds);
        }
    }
}

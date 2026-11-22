package com.investary.notebook.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.AssistantAnswer;
import com.investary.knowledge.assistant.Citation;
import com.investary.knowledge.assistant.PromptBuilder.PriorTurn;
import com.investary.knowledge.assistant.ResearchAssistantService;
import com.investary.knowledge.search.ChunkLookupService;
import com.investary.notebook.application.NotebookExceptions.NotebookEmptyException;
import com.investary.notebook.application.NotebookExceptions.NotebookNotFoundException;
import com.investary.notebook.application.NotebookViews.AskResult;
import com.investary.notebook.application.NotebookViews.CitationView;
import com.investary.notebook.application.NotebookViews.MessageView;
import com.investary.notebook.domain.MessageRole;
import com.investary.notebook.persistence.NotebookStore;
import com.investary.notebook.persistence.NotebookStore.CitationRow;
import com.investary.notebook.persistence.NotebookStore.MessageRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * Chat with a notebook. Retrieval is limited to the notebook's sources; earlier turns are sent as context only, and the
 * answer goes through the research assistant's citation validation. The model is called with no transaction open; the
 * question, the answer and its citations are written together afterwards.
 */
@Service
public class NotebookChatService {

    private static final Logger log = LoggerFactory.getLogger(NotebookChatService.class);

    private final NotebookService notebooks;
    private final NotebookStore store;
    private final ResearchAssistantService assistant;
    private final ChunkLookupService chunks;
    private final NotebookProperties properties;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public NotebookChatService(NotebookService notebooks, NotebookStore store, ResearchAssistantService assistant, ChunkLookupService chunks,
                               NotebookProperties properties, TransactionTemplate transactions, JsonMapper json) {
        this.notebooks = notebooks;
        this.store = store;
        this.assistant = assistant;
        this.chunks = chunks;
        this.properties = properties;
        this.transactions = transactions;
        this.json = json;
    }

    public AskResult ask(UUID notebookId, String question) {
        notebooks.require(notebookId);
        NotebookService.Scope scope = notebooks.scope(notebookId);
        if (scope.searchable().isEmpty()) {
            throw new NotebookEmptyException();
        }
        List<PriorTurn> history = history(notebookId);
        AssistantAnswer answer = assistant.ask(question.strip(), scope.filters(), properties.chatSources(), history);

        Map<UUID, String> hashes = chunks.currentHashes(answer.citations().stream().map(Citation::chunkId).toList());
        List<CitationRow> citations = answer.citations().stream().map(c -> row(c, hashes.get(c.chunkId()))).toList();
        MessageRow asked = new MessageRow(UUID.randomUUID(), notebookId, MessageRole.USER, question.strip(), null, "[]", null, null);
        MessageRow answered = new MessageRow(UUID.randomUUID(), notebookId, MessageRole.ASSISTANT, answer.answer(),
                answer.evidenceStatus().name(), NotebookJson.write(json, answer.warnings()), answer.model(), null);
        transactions.executeWithoutResult(tx -> {
            store.lock(notebookId).orElseThrow(() -> new NotebookNotFoundException(notebookId));
            store.insertMessage(asked);
            store.insertMessage(answered);
            store.insertCitations(answered.id(), null, citations);
            store.touch(notebookId);
        });
        log.info("Notebook question answered notebook={} status={} cited={} historyTurns={}",
                notebookId, answer.evidenceStatus(), citations.size(), history.size());
        List<MessageView> saved = messages(notebookId, List.of(asked.id(), answered.id()));
        return new AskResult(saved.get(0), saved.get(1), answer.coverageGaps(), answer.disclaimer());
    }

    public List<MessageView> messages(UUID notebookId) {
        notebooks.require(notebookId);
        return views(store.messages(notebookId));
    }

    public void clear(UUID notebookId) {
        transactions.executeWithoutResult(tx -> {
            store.lock(notebookId).orElseThrow(() -> new NotebookNotFoundException(notebookId));
            int removed = store.deleteMessages(notebookId);
            log.info("Notebook chat cleared notebook={} messages={}", notebookId, removed);
        });
    }

    /** The last complete question/answer pairs, oldest first. */
    private List<PriorTurn> history(UUID notebookId) {
        int turns = properties.chatHistoryTurns();
        if (turns == 0) {
            return List.of();
        }
        List<MessageRow> recent = store.recentMessages(notebookId, turns * 2 + 1);
        List<PriorTurn> history = new ArrayList<>();
        for (int i = 0; i + 1 < recent.size(); i++) {
            MessageRow q = recent.get(i);
            MessageRow a = recent.get(i + 1);
            if (q.role() == MessageRole.USER && a.role() == MessageRole.ASSISTANT) {
                history.add(new PriorTurn(q.content(), a.content()));
                i++;
            }
        }
        return history.size() > turns ? history.subList(history.size() - turns, history.size()) : history;
    }

    private List<MessageView> messages(UUID notebookId, List<UUID> ids) {
        // Recent messages may include a concurrent question; keep only the two just written, question first.
        List<MessageRow> recent = store.recentMessages(notebookId, 2 * ids.size() + 4).stream().filter(row -> ids.contains(row.id())).toList();
        return views(recent);
    }

    private List<MessageView> views(List<MessageRow> rows) {
        Map<UUID, List<CitationRow>> citations = store.messageCitations(rows.stream().map(MessageRow::id).toList());
        return rows.stream().map(row -> new MessageView(row.id(), row.role(), row.content(), row.evidenceStatus(),
                NotebookJson.strings(json, row.warningsJson()), row.model(), row.createdAt(),
                citations.getOrDefault(row.id(), List.of()).stream().map(NotebookChatService::view).toList())).toList();
    }

    static CitationRow row(Citation citation, String sha256) {
        return new CitationRow(citation.label(), citation.sourceType().name(), citation.sourceId(), citation.chunkId(), sha256,
                citation.title() == null ? "" : citation.title(), citation.publisher(), citation.publishedAt(), citation.dateBasis(),
                citation.location(), citation.excerpt() == null ? "" : citation.excerpt());
    }

    static CitationView view(CitationRow row) {
        return new CitationView(row.label(), KnowledgeSourceType.valueOf(row.sourceType()), row.sourceId(), row.sourceId() != null,
                row.chunkId(), row.title(), row.publisher(), row.publishedOn(), row.dateBasis(), row.location(), row.excerpt());
    }
}

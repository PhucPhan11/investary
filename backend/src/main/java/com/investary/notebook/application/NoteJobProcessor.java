package com.investary.notebook.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.common.error.ErrorSummaries;
import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.ChatGateway;
import com.investary.knowledge.assistant.Citations;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ChunkLookupService;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceState;
import com.investary.knowledge.usage.AiPurpose;
import com.investary.knowledge.usage.AiUsageService;
import com.investary.notebook.application.NoteComposer.Composition;
import com.investary.notebook.application.NoteComposer.Rating;
import com.investary.notebook.application.NoteEvidenceCollector.NoteEvidence;
import com.investary.notebook.persistence.NoteQueue;
import com.investary.notebook.persistence.NoteQueue.ClaimedNote;
import com.investary.notebook.persistence.NotebookStore;
import com.investary.notebook.persistence.NotebookStore.CitationRow;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.ResearchQueryService.SourceRecommendation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one claimed note: evidence and the model call happen with no transaction open; the content and its citations
 * are written in one short transaction that first re-checks the claim token. Transient failures are retried with
 * backoff; after the last attempt the note is FAILED with a safe error summary.
 */
@Service
public class NoteJobProcessor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NoteJobProcessor.class);

    private final NotebookService notebooks;
    private final NoteEvidenceCollector collector;
    private final NoteQueue queue;
    private final NotebookStore store;
    private final ResearchQueryService research;
    private final ChunkLookupService chunks;
    private final NotebookProperties properties;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final ChatGateway chat;
    private final String modelLabel;

    NoteJobProcessor(NotebookService notebooks, NoteEvidenceCollector collector, NoteQueue queue, NotebookStore store,
                     ResearchQueryService research, ChunkLookupService chunks, NotebookProperties properties,
                     TransactionTemplate transactions, JsonMapper json, Clock clock, ObjectProvider<ChatModel> chatModels,
                     KnowledgeProperties knowledge, AiConcurrencyLimits limits, AiUsageService usage) {
        this.notebooks = notebooks;
        this.collector = collector;
        this.queue = queue;
        this.store = store;
        this.research = research;
        this.chunks = chunks;
        this.properties = properties;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.modelLabel = knowledge.assistant().modelLabel();
        this.chat = new ChatGateway(chatModels, properties.aiTimeout(), limits, usage, AiPurpose.NOTEBOOK_NOTE, modelLabel);
    }

    public void process(ClaimedNote note) {
        try {
            Draft draft = generate(note);
            apply(note, draft);
        } catch (AiNotConfiguredException e) {
            fail(note, "AI_NOT_CONFIGURED", "No chat model is configured");
        } catch (AiProviderException e) {
            retryOrFail(note, "AI_PROVIDER_ERROR", e.getMessage(), e.isRetryable());
        } catch (UnreadableReply e) {
            retryOrFail(note, "UNREADABLE_MODEL_REPLY", e.getMessage(), true);
        } catch (RuntimeException e) {
            String code = e instanceof com.investary.common.error.ApiException api ? api.getCode().name() : "GENERATION_FAILED";
            log.warn("Notebook note {} failed (attempt {}/{}): {}", note.id(), note.attempt(), note.maxAttempts(), code);
            retryOrFail(note, code, e.getMessage(), true);
        }
    }

    Draft generate(ClaimedNote note) {
        List<SourceState> searchable = notebooks.scope(note.notebookId()).searchable();
        NoteEvidence evidence = collector.collect(searchable, note.kind(), note.ticker());
        if (evidence.empty()) {
            return new Draft(NoteComposer.empty(note.kind(), note.ticker(), evidence), evidence, null);
        }
        if (!chat.isAvailable()) {
            throw new AiNotConfiguredException("chat");
        }
        LocalDate today = LocalDate.now(clock.withZone(NoteService.ZONE));
        ChatGateway.Reply reply = chat.callWithUsage(NotePrompts.build(note.kind(), note.ticker(), evidence, today));
        JsonNode node = ChatGateway.jsonObject(reply.text(), json)
                .orElseThrow(() -> new UnreadableReply("The model reply was not the requested JSON"));
        Composition composition = NoteComposer.compose(note.kind(), note.ticker(), node, evidence, ratings(note, evidence));
        return new Draft(composition, evidence, modelLabel);
    }

    /** Latest extracted rating per email in the evidence, for the note's ticker. */
    private Map<UUID, Rating> ratings(ClaimedNote note, NoteEvidence evidence) {
        if (note.ticker() == null) {
            return Map.of();
        }
        List<UUID> emails = evidence.groups().stream().map(NoteEvidenceCollector.SourceGroup::source)
                .filter(s -> s.sourceType() == KnowledgeSourceType.EMAIL).map(SourceState::sourceId).toList();
        Map<UUID, Rating> ratings = new HashMap<>();
        for (SourceRecommendation recommendation : research.recommendations(emails, note.ticker())) {
            JsonNode data = recommendation.item().structuredData();
            String rating = data == null ? null : data.path("sourceRecommendation").asString(null);
            if (rating != null && !rating.isBlank()) {
                ratings.put(recommendation.messageId(), new Rating(rating.strip(), recommendation.item().sourceLocation()));
            }
        }
        return ratings;
    }

    private void apply(ClaimedNote note, Draft draft) {
        Composition composition = draft.composition();
        Map<String, LabeledHit> byLabel = new LinkedHashMap<>();
        draft.evidence().labeled().forEach(hit -> byLabel.put(hit.label(), hit));
        List<LabeledHit> cited = composition.citedLabels().stream().map(byLabel::get).filter(java.util.Objects::nonNull).toList();
        Map<UUID, String> hashes = chunks.currentHashes(cited.stream().map(hit -> hit.hit().chunkId()).toList());
        List<CitationRow> citations = cited.stream()
                .map(hit -> NotebookChatService.row(Citations.of(hit, List.of()), hashes.get(hit.hit().chunkId())))
                .toList();
        String content = json.writeValueAsString(composition.content());
        String limitations = json.writeValueAsString(composition.limitations());
        transactions.executeWithoutResult(status -> {
            if (!queue.lockClaimed(note)) {
                log.info("Notebook note {} is no longer claimed by this worker; result dropped", note.id());
                return;
            }
            if (queue.complete(note, content, limitations, composition.status().name(), draft.model())) {
                store.insertCitations(null, note.id(), citations);
            }
        });
        log.info("Notebook note generated note={} kind={} status={} cited={} limitations={}",
                note.id(), note.kind(), composition.status(), citations.size(), composition.limitations().size());
    }

    private void retryOrFail(ClaimedNote note, String code, String message, boolean retryable) {
        String summary = Optional.ofNullable(ErrorSummaries.safe(message)).orElse(code);
        if (retryable && note.attempt() < note.maxAttempts()) {
            Instant next = Instant.now().plus(ErrorSummaries.backoff(properties.retryBackoff(), note.attempt(),
                    ThreadLocalRandom.current().nextDouble()));
            queue.retryLater(note, code, summary, next);
            return;
        }
        fail(note, code, summary);
    }

    private void fail(ClaimedNote note, String code, String summary) {
        if (queue.fail(note, code, ErrorSummaries.safe(summary))) {
            log.warn("Notebook note {} failed: {}", note.id(), code);
        }
    }

    @Override
    public void destroy() {
        chat.close();
    }

    /** @param model null when no model was called */
    record Draft(Composition composition, NoteEvidence evidence, String model) {
    }

    static final class UnreadableReply extends RuntimeException {
        UnreadableReply(String message) {
            super(message);
        }
    }
}

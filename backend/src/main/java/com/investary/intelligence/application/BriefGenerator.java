package com.investary.intelligence.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.investary.common.error.ErrorSummaries;
import com.investary.common.util.Hashing;
import com.investary.intelligence.application.BriefComposer.Composition;
import com.investary.intelligence.application.BriefEvidenceCollector.BriefEvidence;
import com.investary.intelligence.application.BriefEvidenceCollector.Entry;
import com.investary.intelligence.application.BriefPrompts.PlanLine;
import com.investary.intelligence.application.BriefValidator.Review;
import com.investary.intelligence.application.BriefViews.Limitation;
import com.investary.intelligence.domain.BriefAiStatus;
import com.investary.intelligence.domain.BriefSourceRole;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.Relevance;
import com.investary.intelligence.domain.ResearchState;
import com.investary.intelligence.persistence.BriefJobQueue.ClaimedJob;
import com.investary.intelligence.persistence.BriefStore;
import com.investary.intelligence.persistence.BriefStore.BriefRow;
import com.investary.intelligence.persistence.BriefStore.NewSource;
import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.AiExceptions.AiTimeoutException;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.assistant.ChatGateway;
import com.investary.knowledge.assistant.Citations;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ResearchAvailabilityService.PendingSource;
import com.investary.research.link.Keywords;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Produces the content of one brief generation without touching job or brief rows, and without a
 * transaction open (the model call can take minutes):
 * <ol>
 *   <li>collect the deterministic evidence package;</li>
 *   <li>compute the input fingerprint; identical inputs to the latest revision end here (no model call);</li>
 *   <li>call the model once (optional) and validate its JSON with {@link BriefValidator};</li>
 *   <li>compose the sections with {@link BriefComposer} and the source rows to store.</li>
 * </ol>
 * A retryable model failure with attempts left is thrown as {@link RetryLater}; on the last attempt the
 * brief is written without the model part (PARTIAL).
 */
@Service
class BriefGenerator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BriefGenerator.class);

    private final BriefEvidenceCollector collector;
    private final BriefStore store;
    private final BriefProperties properties;
    private final KnowledgeProperties.Assistant assistant;
    private final IndexVersion indexVersion;
    private final ChatGateway chat;
    private final JsonMapper json;
    private final Clock clock;

    BriefGenerator(BriefEvidenceCollector collector, BriefStore store, BriefProperties properties, KnowledgeProperties knowledge,
                   IndexVersion indexVersion, ObjectProvider<ChatModel> chatModels, AiConcurrencyLimits limits, JsonMapper json, Clock clock) {
        this.collector = collector;
        this.store = store;
        this.properties = properties;
        this.assistant = knowledge.assistant();
        this.indexVersion = indexVersion;
        this.chat = new ChatGateway(chatModels, properties.aiTimeout(), limits);
        this.json = json;
        this.clock = clock;
    }

    Draft generate(ClaimedJob job) {
        Instant cutoff = clock.instant();
        ZoneId zone = ZoneId.of(job.zone());
        BriefEvidence evidence = collector.collect(job.portfolioId(), job.periodStart(), job.periodEnd(), zone, cutoff);
        String period = BriefComposer.period(job.periodStart(), job.periodEnd(), zone);
        String facts = evidence.snapshot() == null ? "Không có danh mục (bản tin chung).\n" : PortfolioContext.facts(evidence.snapshot());
        String profile = PortfolioContext.profile(evidence.profile());
        String goals = PortfolioContext.goals(evidence.goals());
        String snapshotHash = evidence.snapshot() == null ? null : ImpactAssessmentService.snapshotHash(evidence.snapshot());
        boolean chatAvailable = chat.isAvailable();
        String fingerprint = fingerprint(job, evidence, snapshotHash, profile + goals, chatAvailable);

        Optional<BriefRow> head = job.trigger() == BriefTrigger.REGENERATION ? store.headOfChain(job.previousBriefId())
                : store.head(job.portfolioId(), job.periodStart(), job.periodEnd());
        if (head.isPresent() && fingerprint.equals(head.get().inputFingerprint()) && reusable(head.get())) {
            log.info("Brief inputs unchanged job={} brief={}", job.id(), head.get().id());
            return Draft.unchanged(head.get().id());
        }

        List<Limitation> limitations = new ArrayList<>();
        if (job.partialPeriod()) {
            limitations.add(new Limitation("PARTIAL_PERIOD", "Kỳ báo cáo chưa kết thúc; bản tin chỉ gồm nguồn có đến "
                    + BriefComposer.period(job.periodStart(), job.periodEnd(), zone) + "."));
        }
        boolean skip = evidence.state() == ResearchState.NO_MATERIAL_RESEARCH && properties.noResearchMode() == BriefProperties.NoResearchMode.SKIP;
        BriefAiStatus aiStatus;
        Review review = null;
        boolean outputInvalid = false;
        String model = null;
        Integer inputTokens = null;
        Integer outputTokens = null;
        String errorCode = null;
        String errorSummary = null;
        if (evidence.state() != ResearchState.NEW_RESEARCH || evidence.entries().isEmpty()) {
            aiStatus = BriefAiStatus.SKIPPED_NO_EVIDENCE;
        } else if (!chatAvailable) {
            aiStatus = BriefAiStatus.NOT_CONFIGURED;
            limitations.add(new Limitation("AI_NOT_CONFIGURED", "Chưa cấu hình mô hình AI; bản tin chỉ gồm phần tổng hợp tự động."));
        } else {
            Plan plan = plan(evidence);
            try {
                ChatGateway.Reply reply = chat.callWithUsage(BriefPrompts.brief(period, facts, profile, goals, plan.lines(), evidence.labeled()));
                aiStatus = BriefAiStatus.COMPLETED;
                model = assistant.modelLabel();
                inputTokens = reply.inputTokens();
                outputTokens = reply.outputTokens();
                Optional<JsonNode> node = ChatGateway.jsonObject(reply.text(), json);
                if (node.isEmpty()) {
                    outputInvalid = true;
                    limitations.add(new Limitation("AI_OUTPUT_INVALID",
                            "Câu trả lời của mô hình không đúng định dạng; chỉ giữ phần tổng hợp tự động."));
                } else {
                    String allowedFigures = facts + "\n" + period + "\n" + evidence.newSources() + " " + evidence.pending().size();
                    review = BriefValidator.review(node.get(), plan.holdings(), plan.opportunities(), plan.market(), plan.sources(),
                            allowedFigures);
                    review.warnings().forEach(w -> limitations.add(new Limitation("AI_VALIDATION", w)));
                }
            } catch (AiNotConfiguredException e) {
                aiStatus = BriefAiStatus.NOT_CONFIGURED;
                limitations.add(new Limitation("AI_NOT_CONFIGURED", "Chưa cấu hình mô hình AI; bản tin chỉ gồm phần tổng hợp tự động."));
            } catch (AiTimeoutException | AiProviderException e) {
                boolean timeout = e instanceof AiTimeoutException;
                String code = timeout ? "AI_TIMEOUT" : "AI_PROVIDER_ERROR";
                String summary = timeout ? "The AI model did not answer in time" : ErrorSummaries.safe(e.getMessage());
                boolean retryable = timeout || ((AiProviderException) e).isRetryable();
                if (retryable && job.attempts() < job.maxAttempts()) {
                    throw new RetryLater(code, summary);
                }
                aiStatus = BriefAiStatus.FAILED;
                errorCode = code;
                errorSummary = summary;
                limitations.add(new Limitation("AI_FAILED", (timeout ? "Mô hình AI không trả lời kịp" : "Mô hình AI gặp lỗi")
                        + "; bản tin chỉ gồm phần tổng hợp tự động."));
            }
        }

        Composition composition = BriefComposer.compose(evidence, job.periodStart(), job.periodEnd(), zone, review, limitations);
        BriefStatus status = skip ? BriefStatus.SKIPPED
                : evidence.state() == ResearchState.NEW_RESEARCH && (aiStatus != BriefAiStatus.COMPLETED && aiStatus != BriefAiStatus.SKIPPED_NO_EVIDENCE
                || outputInvalid) ? BriefStatus.PARTIAL : BriefStatus.COMPLETED;
        String snapshotJson = evidence.snapshot() == null ? null : json.writeValueAsString(ImpactAssessmentService.snapshot(evidence.snapshot()));
        log.info("Brief generated job={} portfolio={} state={} sources={} pending={} ai={} status={} warnings={}", job.id(),
                job.portfolioId(), evidence.state(), evidence.newSources(), evidence.pending().size(), aiStatus, status,
                composition.limitations().size());
        return new Draft(false, null, status, evidence.state(), json.writeValueAsString(composition.content()),
                json.writeValueAsString(composition.limitations()), aiStatus, model, indexVersion.current(), fingerprint, snapshotJson,
                snapshotHash, composition.evidenceStatus(), errorCode, errorSummary, inputTokens, outputTokens, cutoff,
                sources(evidence, composition));
    }

    /** Model output that was reused, or that the model never produced, does not need a new revision. */
    private static boolean reusable(BriefRow head) {
        return head.status() == BriefStatus.COMPLETED || head.status() == BriefStatus.SKIPPED
                || head.status() == BriefStatus.PARTIAL && head.aiStatus() == BriefAiStatus.NOT_CONFIGURED;
    }

    private String fingerprint(ClaimedJob job, BriefEvidence evidence, String snapshotHash, String profileAndGoals, boolean chatAvailable) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(BriefPrompts.VERSION).append('|').append(indexVersion.current()).append('|').append(assistant.modelLabel())
                .append('|').append(chatAvailable).append('|').append(properties.configVersion()).append('|').append(job.periodStart())
                .append('|').append(job.periodEnd()).append('|').append(job.portfolioId()).append('|').append(snapshotHash).append('\n')
                .append(profileAndGoals).append('\n');
        for (LabeledHit hit : evidence.labeled()) {
            canonical.append(hit.label()).append(':').append(hit.hit().chunkId()).append(':')
                    .append(evidence.chunkHashes().get(hit.hit().chunkId())).append('\n');
        }
        evidence.omitted().forEach(e -> canonical.append("omitted:").append(e.source().knowledgeSourceId()).append('\n'));
        evidence.pending().forEach(p -> canonical.append("pending:").append(p.sourceId()).append(':').append(p.state()).append('\n'));
        evidence.assessments().forEach((symbol, ref) -> canonical.append("assessment:").append(ref.assessmentId()).append('\n'));
        return Hashing.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Which labels each part of the brief may cite. */
    private static Plan plan(BriefEvidence evidence) {
        Map<String, List<String>> holdings = new LinkedHashMap<>();
        Map<String, List<String>> opportunities = new LinkedHashMap<>();
        List<String> market = new ArrayList<>();
        for (Entry entry : evidence.entries()) {
            entry.heldSymbols().forEach(symbol -> holdings.computeIfAbsent(symbol.toUpperCase(Locale.ROOT), s -> new ArrayList<>())
                    .addAll(entry.labels()));
            if (entry.relevance() == Relevance.MARKET) {
                market.addAll(entry.labels());
            }
            if (entry.relevance() == Relevance.OTHER) {
                entry.otherTickers().forEach(ticker -> opportunities.computeIfAbsent(ticker.toUpperCase(Locale.ROOT), s -> new ArrayList<>())
                        .addAll(entry.labels()));
            }
        }
        List<PlanLine> lines = new ArrayList<>();
        holdings.forEach((symbol, labels) -> lines.add(new PlanLine("holding", symbol, labels)));
        if (!market.isEmpty()) {
            lines.add(new PlanLine("market", null, market));
        }
        opportunities.forEach((ticker, labels) -> lines.add(new PlanLine("opportunity", ticker, labels)));
        Map<String, String> sources = new LinkedHashMap<>();
        evidence.labeled().forEach(hit -> sources.put(hit.label(), hit.hit().content()));
        return new Plan(lines, holdings, opportunities, market, sources);
    }

    private static List<NewSource> sources(BriefEvidence evidence, Composition composition) {
        List<NewSource> rows = new ArrayList<>();
        for (Entry entry : evidence.entries()) {
            List<String> terms = new ArrayList<>(entry.heldSymbols());
            terms.addAll(entry.otherTickers());
            List<String> folded = terms.stream().map(Keywords::normalize).toList();
            List<String> symbols = entry.heldSymbols().isEmpty() ? entry.otherTickers() : entry.heldSymbols();
            for (LabeledHit hit : entry.hits()) {
                var citation = Citations.of(hit, folded);
                rows.add(new NewSource(composition.citedLabels().contains(hit.label()) ? BriefSourceRole.CITED : BriefSourceRole.INCLUDED,
                        hit.label(), entry.source().sourceType(), entry.source().sourceId(), hit.hit().chunkId(),
                        evidence.chunkHashes().get(hit.hit().chunkId()), entry.source().title(), entry.source().brokerage(),
                        entry.source().publishedOn(), entry.source().dateBasis(), entry.source().sentAt(), entry.source().importedAt(),
                        entry.source().firstIndexedAt(), hit.hit().pageStart(), hit.hit().pageEnd(), hit.hit().section(),
                        citation.location(), citation.excerpt(), symbols, entry.relevance(), "INDEXED", entry.oldPublication(),
                        entry.lateArrival(), entry.receivedVia()));
            }
        }
        for (Entry entry : evidence.omitted()) {
            rows.add(new NewSource(BriefSourceRole.OMITTED, null, entry.source().sourceType(), entry.source().sourceId(), null, null,
                    entry.source().title(), entry.source().brokerage(), entry.source().publishedOn(), entry.source().dateBasis(),
                    entry.source().sentAt(), entry.source().importedAt(), entry.source().firstIndexedAt(), null, null, null, null, null,
                    entry.heldSymbols().isEmpty() ? entry.otherTickers() : entry.heldSymbols(), entry.relevance(), "INDEXED",
                    entry.oldPublication(), entry.lateArrival(), entry.receivedVia()));
        }
        for (PendingSource pending : evidence.pending()) {
            rows.add(new NewSource(BriefSourceRole.PENDING, null, pending.sourceType(), pending.sourceId(), null, null, pending.title(),
                    pending.brokerage(), null, null, pending.sentAt(), pending.importedAt(), null, null, null, null, null, null, List.of(),
                    null, pending.state(), false, false, List.of()));
        }
        return rows;
    }

    @Override
    public void destroy() {
        chat.close();
    }

    private record Plan(List<PlanLine> lines, Map<String, List<String>> holdings, Map<String, List<String>> opportunities,
                        List<String> market, Map<String, String> sources) {
    }

    /**
     * Everything to write for one generation, or {@code unchanged} with the brief that already has these inputs.
     */
    record Draft(boolean unchanged, UUID unchangedBriefId, BriefStatus status, ResearchState researchState, String contentJson,
                 String limitationsJson, BriefAiStatus aiStatus, String model, String indexVersion, String inputFingerprint,
                 String portfolioSnapshotJson, String snapshotHash, EvidenceStatus evidenceStatus, String errorCode, String errorSummary,
                 Integer inputTokens, Integer outputTokens, Instant sourceCutoff, List<NewSource> sources) {

        static Draft unchanged(UUID briefId) {
            return new Draft(true, briefId, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    List.of());
        }
    }

    /** A retryable model failure with attempts left: the job is retried later, nothing is written. */
    static class RetryLater extends RuntimeException {

        private final String code;

        RetryLater(String code, String summary) {
            super(summary);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}

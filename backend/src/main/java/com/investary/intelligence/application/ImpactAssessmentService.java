package com.investary.intelligence.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.common.util.Hashing;
import com.investary.intelligence.application.IntelligenceExceptions.AssessmentNotFoundException;
import com.investary.intelligence.application.IntelligencePrompts.HoldingPrompt;
import com.investary.intelligence.application.IntelligenceViews.AssessmentItemView;
import com.investary.intelligence.application.IntelligenceViews.AssessmentListItem;
import com.investary.intelligence.application.IntelligenceViews.AssessmentSnapshot;
import com.investary.intelligence.application.IntelligenceViews.AssessmentView;
import com.investary.intelligence.application.IntelligenceViews.EvidenceReference;
import com.investary.intelligence.application.IntelligenceViews.SnapshotHolding;
import com.investary.intelligence.application.IntelligenceViews.StaleReason;
import com.investary.intelligence.application.ResearchMatcher.HoldingResearch;
import com.investary.intelligence.application.ResearchMatcher.MatchedEvidence;
import com.investary.intelligence.application.ResearchMatcher.PortfolioResearch;
import com.investary.intelligence.application.ResearchMatcher.ResearchFilters;
import com.investary.intelligence.domain.AiStatus;
import com.investary.intelligence.domain.ImpactAssessment;
import com.investary.intelligence.domain.ImpactAssessmentItem;
import com.investary.intelligence.domain.ImpactCategory;
import com.investary.intelligence.persistence.ImpactAssessmentItemRepository;
import com.investary.intelligence.persistence.ImpactAssessmentRepository;
import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.assistant.ChatGateway;
import com.investary.knowledge.assistant.CitationValidator;
import com.investary.knowledge.assistant.Citations;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ChunkLookupService;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.research.link.Keywords;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio impact assessments: deterministic matching first, then an optional AI interpretation that
 * can only choose among categories allowed by the evidence and must cite that holding's evidence.
 * <ol>
 *   <li>Match research per holding ({@link ResearchMatcher}) and derive a deterministic category:
 *       direct research → DIRECT_HOLDING, passing mentions → MONITORING_ONLY, only market commentary →
 *       MARKET_CONTEXT, nothing → INSUFFICIENT_EVIDENCE.</li>
 *   <li>Without evidence the model is not called; without a chat model the deterministic result is saved.</li>
 *   <li>The model's category must be allowed for the holding's evidence tier, and its summary is checked by
 *       {@link CitationValidator} against that holding's sources only. Anything that fails falls back to
 *       the deterministic result, with a warning.</li>
 *   <li>The assessment is saved with a portfolio snapshot and evidence hashes; it is reported stale when
 *       holdings or cited chunks change later.</li>
 * </ol>
 * The model never sees or changes anything it could write back: this service only reads portfolio data.
 */
@Service
public class ImpactAssessmentService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImpactAssessmentService.class);

    private static final Set<ImpactCategory> DIRECT_ALLOWED = EnumSet.of(ImpactCategory.DIRECT_HOLDING, ImpactCategory.POTENTIAL_RISK,
            ImpactCategory.POTENTIAL_OPPORTUNITY, ImpactCategory.MONITORING_ONLY, ImpactCategory.INSUFFICIENT_EVIDENCE);
    private static final Set<ImpactCategory> MENTION_ALLOWED = EnumSet.of(ImpactCategory.POTENTIAL_RISK,
            ImpactCategory.POTENTIAL_OPPORTUNITY, ImpactCategory.MONITORING_ONLY, ImpactCategory.INSUFFICIENT_EVIDENCE);
    private static final Set<ImpactCategory> MARKET_ALLOWED = EnumSet.of(ImpactCategory.MARKET_CONTEXT,
            ImpactCategory.MONITORING_ONLY, ImpactCategory.INSUFFICIENT_EVIDENCE);

    private final PortfolioQueryService portfolios;
    private final ResearchMatcher matcher;
    private final ChunkLookupService chunks;
    private final ImpactAssessmentRepository assessments;
    private final ImpactAssessmentItemRepository items;
    private final ChatGateway chat;
    private final KnowledgeProperties.Assistant settings;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final IndexVersion indexVersion;
    private final Clock clock;

    public ImpactAssessmentService(PortfolioQueryService portfolios, ResearchMatcher matcher, ChunkLookupService chunks,
                                   ImpactAssessmentRepository assessments, ImpactAssessmentItemRepository items,
                                   ObjectProvider<ChatModel> chatModels, KnowledgeProperties knowledge, JsonMapper json,
                                   TransactionTemplate transactions, IndexVersion indexVersion, AiConcurrencyLimits limits,
                                   Clock clock) {
        this.portfolios = portfolios;
        this.matcher = matcher;
        this.chunks = chunks;
        this.assessments = assessments;
        this.items = items;
        this.settings = knowledge.assistant();
        this.chat = new ChatGateway(chatModels, settings.timeout(), limits);
        this.json = json;
        this.transactions = transactions;
        this.indexVersion = indexVersion;
        this.clock = clock;
    }

    public AssessmentView create(UUID portfolioId, ResearchFilters filters) {
        return create(portfolioId, filters, null).view();
    }

    /**
     * Creates the assessment a research workflow asks for after new research matched this portfolio. It is
     * idempotent per (portfolio, trigger source, holdings snapshot, source content): an existing one is
     * returned instead of calling the model again.
     */
    public SourceAssessment createForSource(UUID portfolioId, AssessmentTrigger trigger) {
        String currentHash = snapshotHash(portfolios.snapshot(portfolioId));
        var existing = assessments.findFirstByPortfolioIdAndTriggerSourceIdAndSnapshotHashAndTriggerContentSha256(portfolioId,
                trigger.sourceId(), currentHash, trigger.contentSha256());
        if (existing.isPresent()) {
            return new SourceAssessment(existing.get().getId(), false, existing.get().getAiStatus(), null, null);
        }
        try {
            Created created = create(portfolioId, ResearchFilters.NONE, trigger);
            return new SourceAssessment(created.view().id(), true, created.view().aiStatus(), created.inputTokens(),
                    created.outputTokens());
        } catch (DataIntegrityViolationException e) {
            // Lost a race against the same assessment (unique trigger index).
            return assessments.findFirstByPortfolioIdAndTriggerSourceIdAndSnapshotHashAndTriggerContentSha256(portfolioId,
                            trigger.sourceId(), currentHash, trigger.contentSha256())
                    .map(found -> new SourceAssessment(found.getId(), false, found.getAiStatus(), null, null))
                    .orElseThrow(() -> e);
        }
    }

    private Created create(UUID portfolioId, ResearchFilters filters, AssessmentTrigger trigger) {
        PortfolioSnapshot snapshot = portfolios.snapshot(portfolioId);
        PortfolioResearch research = matcher.match(snapshot.instruments(), filters, portfolios.today());
        Map<UUID, HoldingResearch> byInstrument = research.holdings().stream()
                .collect(Collectors.toMap(HoldingResearch::instrumentId, Function.identity()));

        // 1. Deterministic tiers and evidence labels (each chunk labelled once, in holding order).
        Map<UUID, LabeledHit> labeledByChunk = new LinkedHashMap<>();
        Map<UUID, MatchedEvidence> matchedByChunk = new HashMap<>();
        List<Plan> plans = new ArrayList<>();
        int budget = settings.maxContextChars();
        for (HoldingView holding : snapshot.holdings()) {
            HoldingResearch found = byInstrument.get(holding.instrumentId());
            Tier tier = found == null ? Tier.NONE : !found.direct().isEmpty() ? Tier.DIRECT
                    : !found.mentions().isEmpty() ? Tier.MENTION : research.marketContext().isEmpty() ? Tier.NONE : Tier.MARKET;
            List<MatchedEvidence> evidence = switch (tier) {
                case DIRECT, MENTION -> found.all();
                case MARKET -> research.marketContext();
                case NONE -> List.of();
            };
            List<String> labels = new ArrayList<>();
            for (MatchedEvidence match : evidence) {
                LabeledHit labeled = labeledByChunk.get(match.hit().chunkId());
                if (labeled == null && (labeledByChunk.isEmpty() || budget - match.hit().content().length() >= 0)) {
                    budget -= match.hit().content().length();
                    labeled = new LabeledHit("S" + (labeledByChunk.size() + 1), match.hit());
                    labeledByChunk.put(match.hit().chunkId(), labeled);
                    matchedByChunk.put(match.hit().chunkId(), match);
                }
                if (labeled != null) {
                    labels.add(labeled.label());
                }
            }
            plans.add(new Plan(holding, tier, evidence, labels));
        }
        Map<UUID, String> hashes = chunks.currentHashes(labeledByChunk.keySet());
        Map<String, LabeledHit> byLabel = new LinkedHashMap<>();
        labeledByChunk.values().forEach(hit -> byLabel.put(hit.label(), hit));

        // 2. Deterministic items, then the optional AI interpretation.
        List<String> warnings = new ArrayList<>();
        Map<UUID, AssessmentItemView> result = new LinkedHashMap<>();
        plans.forEach(plan -> result.put(plan.holding().instrumentId(), deterministic(plan, byLabel, matchedByChunk, hashes)));
        AiStatus aiStatus;
        String overall = null;
        EvidenceStatus overallStatus = null;
        String model = null;
        Integer inputTokens = null;
        Integer outputTokens = null;
        String facts = PortfolioContext.facts(snapshot);
        if (byLabel.isEmpty()) {
            aiStatus = AiStatus.SKIPPED_NO_EVIDENCE;
        } else if (!chat.isAvailable()) {
            aiStatus = AiStatus.NOT_CONFIGURED;
            warnings.add("Chưa cấu hình mô hình AI; kết quả chỉ gồm đối chiếu tài liệu tự động.");
        } else {
            List<HoldingPrompt> holdingPrompts = plans.stream().filter(plan -> plan.tier() != Tier.NONE && !plan.labels().isEmpty())
                    .map(plan -> new HoldingPrompt(plan.holding().symbol(), allowed(plan.tier()).stream().map(Enum::name).toList(),
                            plan.labels()))
                    .toList();
            ChatGateway.Reply reply = chat.callWithUsage(IntelligencePrompts.assessment(portfolios.today(), facts, holdingPrompts,
                    List.copyOf(byLabel.values())));
            String raw = reply.text();
            inputTokens = reply.inputTokens();
            outputTokens = reply.outputTokens();
            model = settings.modelLabel();
            aiStatus = AiStatus.COMPLETED;
            JsonNode node = ChatGateway.jsonObject(raw, json).orElse(null);
            if (node == null) {
                warnings.add("Câu trả lời của mô hình không đúng định dạng; chỉ giữ kết quả đối chiếu tự động.");
            } else {
                Map<String, JsonNode> aiItems = new HashMap<>();
                node.path("items").forEach(item -> aiItems.putIfAbsent(item.path("symbol").asString("").strip().toUpperCase(Locale.ROOT), item));
                for (Plan plan : plans) {
                    JsonNode item = aiItems.remove(plan.holding().symbol().toUpperCase(Locale.ROOT));
                    AssessmentItemView interpreted = interpret(plan, item, byLabel, matchedByChunk, hashes, facts, warnings);
                    if (interpreted != null) {
                        result.put(plan.holding().instrumentId(), interpreted);
                    }
                }
                if (!aiItems.isEmpty()) {
                    warnings.add("Đã bỏ qua nhận định cho mã không có trong danh mục: " + String.join(", ", aiItems.keySet()) + ".");
                }
                String overallText = node.path("overall").asString("").strip();
                if (!overallText.isEmpty()) {
                    Map<String, String> sources = new LinkedHashMap<>();
                    byLabel.forEach((label, hit) -> sources.put(label, hit.hit().content()));
                    CitationValidator.Result validated = CitationValidator.validate(overallText, EvidenceStatus.SUPPORTED, sources, facts);
                    overall = validated.answer();
                    overallStatus = validated.status();
                    validated.warnings().forEach(w -> warnings.add("Tóm tắt chung: " + w));
                }
            }
        }

        // 3. Persist with the snapshot used.
        AssessmentSnapshot assessmentSnapshot = snapshot(snapshot);
        ImpactAssessment saved = save(portfolioId, assessmentSnapshot, snapshotHash(snapshot), aiStatus, model, overall, overallStatus,
                warnings, List.copyOf(result.values()), trigger);
        log.info("Impact assessment created id={} portfolio={} holdings={} sources={} ai={} warnings={} trigger={}", saved.getId(),
                portfolioId, plans.size(), byLabel.size(), aiStatus, warnings.size(), trigger == null ? null : trigger.sourceId());
        return new Created(new AssessmentView(saved.getId(), portfolioId, saved.getCreatedAt(), aiStatus, model, overall, overallStatus,
                List.copyOf(warnings), false, List.of(), assessmentSnapshot, List.copyOf(result.values()), IntelligenceViews.DISCLAIMER),
                inputTokens, outputTokens);
    }

    public List<AssessmentListItem> list(UUID portfolioId) {
        PortfolioSnapshot snapshot = portfolios.snapshot(portfolioId);
        String currentHash = snapshotHash(snapshot);
        return assessments.findTop20ByPortfolioIdOrderByCreatedAtDesc(portfolioId).stream()
                .map(assessment -> {
                    List<ImpactAssessmentItem> stored = items.findByAssessmentIdOrderByPositionAsc(assessment.getId());
                    List<StaleReason> reasons = staleReasons(assessment, currentHash, evidence(stored));
                    return new AssessmentListItem(assessment.getId(), assessment.getCreatedAt(), assessment.getAiStatus(), stored.size(),
                            !reasons.isEmpty(), reasons);
                })
                .toList();
    }

    public AssessmentView view(UUID id) {
        ImpactAssessment assessment = assessments.findById(id).orElseThrow(() -> new AssessmentNotFoundException(id));
        List<ImpactAssessmentItem> stored = items.findByAssessmentIdOrderByPositionAsc(id);
        List<AssessmentItemView> views = stored.stream().map(item -> new AssessmentItemView(item.getInstrumentId(), item.getSymbol(),
                item.getDeterministicCategory(), item.getCategory(), item.getSummary(), item.isAiInterpretation(),
                item.getEvidenceStatus(), readList(item.getEvidence(), new TypeReference<List<EvidenceReference>>() { })))
                .toList();
        String currentHash = snapshotHash(portfolios.snapshot(assessment.getPortfolioId()));
        List<StaleReason> reasons = staleReasons(assessment, currentHash, views.stream().flatMap(v -> v.evidence().stream()).toList());
        return new AssessmentView(id, assessment.getPortfolioId(), assessment.getCreatedAt(), assessment.getAiStatus(), assessment.getModel(),
                assessment.getOverallSummary(), assessment.getOverallEvidenceStatus(),
                readList(assessment.getWarnings(), new TypeReference<List<String>>() { }), !reasons.isEmpty(), reasons,
                json.readValue(assessment.getPortfolioSnapshot(), AssessmentSnapshot.class), views, IntelligenceViews.DISCLAIMER);
    }

    public void delete(UUID id) {
        transactions.executeWithoutResult(status -> {
            ImpactAssessment assessment = assessments.findById(id).orElseThrow(() -> new AssessmentNotFoundException(id));
            assessments.delete(assessment);
        });
    }

    /**
     * Validates the model's reading of one holding; {@code null} keeps the deterministic item.
     */
    private AssessmentItemView interpret(Plan plan, JsonNode item, Map<String, LabeledHit> byLabel,
                                         Map<UUID, MatchedEvidence> matchedByChunk, Map<UUID, String> hashes, String facts,
                                         List<String> warnings) {
        String symbol = plan.holding().symbol();
        if (plan.tier() == Tier.NONE || plan.labels().isEmpty()) {
            if (item != null) {
                warnings.add("Đã bỏ qua nhận định AI cho " + symbol + " vì không có bằng chứng.");
            }
            return null;
        }
        if (item == null) {
            warnings.add("Mô hình không đánh giá " + symbol + "; giữ kết quả đối chiếu tự động.");
            return null;
        }
        ImpactCategory category;
        try {
            category = ImpactCategory.valueOf(item.path("category").asString("").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add("Loại đánh giá của " + symbol + " không hợp lệ; giữ kết quả đối chiếu tự động.");
            return null;
        }
        if (!allowed(plan.tier()).contains(category)) {
            warnings.add("Loại " + category + " không phù hợp với bằng chứng hiện có cho " + symbol + "; giữ kết quả đối chiếu tự động.");
            return null;
        }
        String summary = item.path("summary").asString("").strip();
        if (summary.isEmpty()) {
            warnings.add("Mô hình không đưa ra tóm tắt cho " + symbol + "; giữ kết quả đối chiếu tự động.");
            return null;
        }
        // Only this holding's evidence (or market context for a market-only holding) may be cited.
        Map<String, String> sources = new LinkedHashMap<>();
        for (String label : plan.labels()) {
            sources.put(label, byLabel.get(label).hit().content());
        }
        CitationValidator.Result validated = CitationValidator.validate(summary, EvidenceStatus.SUPPORTED, sources, facts);
        validated.warnings().forEach(w -> warnings.add(symbol + ": " + w));
        if (validated.status() == EvidenceStatus.INSUFFICIENT && category != ImpactCategory.INSUFFICIENT_EVIDENCE) {
            warnings.add("Nhận định AI cho " + symbol + " bị loại vì không có trích dẫn hợp lệ.");
            return null;
        }
        List<String> terms = List.of(Keywords.normalize(symbol));
        List<EvidenceReference> evidence = validated.citedLabels().stream()
                .map(label -> {
                    LabeledHit hit = byLabel.get(label);
                    return EvidenceReference.of(Citations.of(hit, terms), matchedByChunk.get(hit.hit().chunkId()),
                            hashes.get(hit.hit().chunkId()));
                })
                .toList();
        return new AssessmentItemView(plan.holding().instrumentId(), symbol, deterministicCategory(plan.tier()), category,
                validated.answer(), true, validated.status(), evidence);
    }

    private static AssessmentItemView deterministic(Plan plan, Map<String, LabeledHit> byLabel, Map<UUID, MatchedEvidence> matchedByChunk,
                                                    Map<UUID, String> hashes) {
        HoldingView holding = plan.holding();
        List<EvidenceReference> evidence = plan.evidence().stream()
                .map(match -> {
                    LabeledHit labeled = byLabel.values().stream().filter(l -> l.hit().chunkId().equals(match.hit().chunkId()))
                            .findFirst().orElse(null);
                    return EvidenceReference.of(match, labeled == null ? null : labeled.label(), hashes.get(match.hit().chunkId()));
                })
                .toList();
        ImpactCategory category = deterministicCategory(plan.tier());
        String newest = plan.evidence().stream().map(m -> m.hit().publishedOn()).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).map(Object::toString).orElse("không rõ");
        String summary = switch (plan.tier()) {
            case DIRECT -> "Có " + evidence.size() + " đoạn nghiên cứu liên quan trực tiếp tới " + holding.symbol()
                    + " trong kho tài liệu; nguồn mới nhất ngày " + newest + ".";
            case MENTION -> holding.symbol() + " chỉ được nhắc tới trong " + evidence.size()
                    + " đoạn nghiên cứu (không phải báo cáo riêng); nguồn mới nhất ngày " + newest + ".";
            case MARKET -> "Không có nghiên cứu riêng về " + holding.symbol() + "; chỉ có " + evidence.size()
                    + " đoạn bình luận thị trường chung.";
            case NONE -> "Kho tài liệu chưa có nghiên cứu về " + holding.symbol() + ".";
        };
        return new AssessmentItemView(holding.instrumentId(), holding.symbol(), category, category, summary, false,
                plan.tier() == Tier.NONE ? EvidenceStatus.INSUFFICIENT : EvidenceStatus.SUPPORTED, evidence);
    }

    private ImpactAssessment save(UUID portfolioId, AssessmentSnapshot snapshot, String hash, AiStatus aiStatus, String model,
                                  String overall, EvidenceStatus overallStatus, List<String> warnings, List<AssessmentItemView> views,
                                  AssessmentTrigger trigger) {
        return transactions.execute(status -> {
            ImpactAssessment entity = new ImpactAssessment(portfolioId, clock.instant(),
                    json.writeValueAsString(snapshot), hash, aiStatus, model, overall, overallStatus, json.writeValueAsString(warnings));
            entity.recordProvenance(IntelligencePrompts.VERSION, indexVersion.current(),
                    trigger == null ? null : trigger.sourceType(), trigger == null ? null : trigger.sourceId(),
                    trigger == null ? null : trigger.contentSha256(), trigger == null ? null : trigger.jobId());
            ImpactAssessment assessment = assessments.saveAndFlush(entity);
            int position = 0;
            for (AssessmentItemView view : views) {
                items.save(new ImpactAssessmentItem(assessment.getId(), position++, view.instrumentId(), view.symbol(),
                        view.deterministicCategory(), view.category(), view.summary(), view.aiInterpretation(), view.evidenceStatus(),
                        json.writeValueAsString(view.evidence())));
            }
            items.flush();
            return assessment;
        });
    }

    private List<StaleReason> staleReasons(ImpactAssessment assessment, String currentHash, List<EvidenceReference> evidence) {
        List<StaleReason> reasons = new ArrayList<>();
        if (!assessment.getSnapshotHash().equals(currentHash)) {
            reasons.add(StaleReason.HOLDINGS_CHANGED);
        }
        Map<UUID, String> expected = new HashMap<>();
        evidence.stream().filter(e -> e.chunkId() != null).forEach(e -> expected.put(e.chunkId(), e.contentSha256()));
        if (!expected.isEmpty()) {
            Map<UUID, String> current = chunks.currentHashes(expected.keySet());
            boolean changed = expected.entrySet().stream()
                    .anyMatch(entry -> !Objects.equals(current.get(entry.getKey()), entry.getValue()) || !current.containsKey(entry.getKey()));
            if (changed) {
                reasons.add(StaleReason.EVIDENCE_CHANGED);
            }
        }
        return reasons;
    }

    private List<EvidenceReference> evidence(List<ImpactAssessmentItem> stored) {
        return stored.stream()
                .flatMap(item -> readList(item.getEvidence(), new TypeReference<List<EvidenceReference>>() { }).stream())
                .toList();
    }

    static AssessmentSnapshot snapshot(PortfolioSnapshot snapshot) {
        return new AssessmentSnapshot(snapshot.summary().name(), snapshot.summary().currency(), snapshot.summary().valuationDate(),
                snapshot.summary().valuationStatus().name(), PortfolioContext.plain(snapshot.summary().totalCostBasis()),
                snapshot.holdings().stream().map(h -> new SnapshotHolding(h.instrumentId(), h.symbol(), PortfolioContext.plain(h.quantity()),
                        PortfolioContext.plain(h.costBasis()), PortfolioContext.plain(h.costWeightPct()), h.valuationStatus().name(),
                        h.latestPrice() == null ? null : PortfolioContext.plain(h.latestPrice()), h.priceAsOf())).toList());
    }

    /** Hash of the open positions (instrument, quantity, cost basis); prices do not count as a holdings change. */
    static String snapshotHash(PortfolioSnapshot snapshot) {
        String canonical = snapshot.holdings().stream()
                .sorted(Comparator.comparing(HoldingView::instrumentId))
                .map(h -> h.instrumentId() + "|" + PortfolioContext.plain(h.quantity()) + "|" + PortfolioContext.plain(h.costBasis()))
                .collect(Collectors.joining("\n"));
        return Hashing.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private <T> List<T> readList(String value, TypeReference<List<T>> type) {
        return value == null ? List.of() : json.readValue(value, type);
    }

    static Set<ImpactCategory> allowed(Tier tier) {
        return switch (tier) {
            case DIRECT -> DIRECT_ALLOWED;
            case MENTION -> MENTION_ALLOWED;
            case MARKET -> MARKET_ALLOWED;
            case NONE -> EnumSet.of(ImpactCategory.INSUFFICIENT_EVIDENCE);
        };
    }

    static ImpactCategory deterministicCategory(Tier tier) {
        return switch (tier) {
            case DIRECT -> ImpactCategory.DIRECT_HOLDING;
            case MENTION -> ImpactCategory.MONITORING_ONLY;
            case MARKET -> ImpactCategory.MARKET_CONTEXT;
            case NONE -> ImpactCategory.INSUFFICIENT_EVIDENCE;
        };
    }

    @Override
    public void destroy() {
        chat.close();
    }

    /**
     * The source that made a research workflow ask for an assessment.
     *
     * @param sourceType    EMAIL or DOCUMENT
     * @param contentSha256 fingerprint of the source content (the email and its documents)
     * @param jobId         automation job, recorded for provenance
     */
    public record AssessmentTrigger(String sourceType, UUID sourceId, String contentSha256, UUID jobId) {
    }

    /**
     * @param created      false when an identical earlier assessment was returned (no model call)
     * @param inputTokens  provider-reported prompt tokens, or null
     * @param outputTokens provider-reported completion tokens, or null
     */
    public record SourceAssessment(UUID assessmentId, boolean created, AiStatus aiStatus, Integer inputTokens, Integer outputTokens) {
    }

    private record Created(AssessmentView view, Integer inputTokens, Integer outputTokens) {
    }

    /** Strength of the evidence matched to a holding. */
    enum Tier {
        DIRECT,
        MENTION,
        MARKET,
        NONE
    }

    private record Plan(HoldingView holding, Tier tier, List<MatchedEvidence> evidence, List<String> labels) {
    }
}

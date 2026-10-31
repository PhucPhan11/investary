package com.investary.intelligence.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.intelligence.application.IntelligenceExceptions.PortfolioRequiredException;
import com.investary.investor.application.GoalView;
import com.investary.investor.application.InvestorQueryService;
import com.investary.investor.application.ProfileView;
import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.assistant.ChatGateway;
import com.investary.knowledge.assistant.Citation;
import com.investary.knowledge.assistant.CitationValidator;
import com.investary.knowledge.assistant.Citations;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;
import com.investary.research.link.Keywords;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio-aware questions. Combines the investor profile, goals and computed holdings with research
 * retrieved by the Phase 3 search, asks the chat model once, and validates the answer with the Phase 3
 * {@link CitationValidator}. Portfolio facts are passed to the validator as known context, so restating
 * them is not flagged as an uncited figure; research claims still need valid citations.
 * <p>
 * The portfolio must be named explicitly: with several portfolios Investary never picks one. The model
 * only reads; nothing it returns is written anywhere.
 */
@Service
public class PortfolioAssistantService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAssistantService.class);
    private static final int HOLDING_HITS = 2;

    private final PortfolioQueryService portfolios;
    private final InvestorQueryService investor;
    private final HybridSearchService search;
    private final IntelligenceProperties properties;
    private final KnowledgeProperties.Assistant settings;
    private final ChatGateway chat;
    private final JsonMapper json;

    public PortfolioAssistantService(PortfolioQueryService portfolios, InvestorQueryService investor, HybridSearchService search,
                                     IntelligenceProperties properties, KnowledgeProperties knowledge,
                                     ObjectProvider<ChatModel> chatModels, JsonMapper json, AiConcurrencyLimits limits) {
        this.portfolios = portfolios;
        this.investor = investor;
        this.search = search;
        this.properties = properties;
        this.settings = knowledge.assistant();
        this.chat = new ChatGateway(chatModels, settings.timeout(), limits);
        this.json = json;
    }

    /**
     * @param portfolioFacts the backend-computed facts that were given to the model
     * @param focusSymbols   holdings searched individually for this question
     */
    public record PortfolioAnswer(UUID portfolioId, String portfolioName, PortfolioSummary portfolioSummary,
                                  List<HoldingView> holdings, String portfolioFacts, boolean profileConfigured, int goalCount,
                                  List<String> focusSymbols, String answer, EvidenceStatus evidenceStatus, List<Citation> citations,
                                  List<UUID> retrievedSourceIds, List<String> warnings, LocalDate newestEvidenceDate, String model,
                                  String disclaimer) {
    }

    public PortfolioAnswer ask(String question, UUID portfolioId, Integer maxSources) {
        if (portfolioId == null) {
            throw new PortfolioRequiredException(portfolios.portfolios().size());
        }
        if (!chat.isAvailable()) {
            throw new AiNotConfiguredException("chat");
        }
        PortfolioSnapshot snapshot = portfolios.snapshot(portfolioId);
        ProfileView profile = investor.profile();
        List<GoalView> goals = investor.goals();
        String facts = PortfolioContext.facts(snapshot);

        SearchResult general = search.search(question, SearchFilters.NONE, maxSources);
        List<InstrumentView> focus = focus(question, general.analysis().tickers(), snapshot);
        Map<UUID, SearchHit> hits = new LinkedHashMap<>();
        general.hits().forEach(hit -> hits.putIfAbsent(hit.chunkId(), hit));
        for (InstrumentView instrument : focus) {
            search.search(question + " " + instrument.symbol() + " " + instrument.name(),
                            new SearchFilters(instrument.symbol(), null, null, null, null), HOLDING_HITS)
                    .hits().forEach(hit -> hits.putIfAbsent(hit.chunkId(), hit));
        }
        List<String> focusSymbols = focus.stream().map(InstrumentView::symbol).toList();
        List<SearchHit> evidence = List.copyOf(hits.values());
        if (evidence.isEmpty()) {
            log.info("Portfolio question without evidence portfolio={} focus={}", portfolioId, focusSymbols.size());
            return new PortfolioAnswer(portfolioId, snapshot.summary().name(), snapshot.summary(), snapshot.holdings(), facts,
                    profile.configured(), goals.size(), focusSymbols,
                    "Không tìm thấy tài liệu nghiên cứu phù hợp trong kho để trả lời câu hỏi này, nên trợ lý không đưa ra nhận định. "
                            + "Dữ liệu danh mục ở trên được tính từ giao dịch bạn đã nhập.",
                    EvidenceStatus.INSUFFICIENT, List.of(), List.of(), List.of(), null, null, IntelligenceViews.DISCLAIMER);
        }

        List<LabeledHit> labeled = Citations.label(evidence, settings.maxContextChars());
        Map<String, LabeledHit> byLabel = new LinkedHashMap<>();
        labeled.forEach(hit -> byLabel.put(hit.label(), hit));
        String raw = chat.call(IntelligencePrompts.ask(portfolios.today(), question, PortfolioContext.profile(profile),
                PortfolioContext.goals(goals), facts, labeled));

        List<String> warnings = new ArrayList<>();
        String answer;
        EvidenceStatus status;
        List<String> unsupported = new ArrayList<>();
        JsonNode node = ChatGateway.jsonObject(raw, json).orElse(null);
        if (node != null && !node.path("answer").asString("").isBlank()) {
            answer = node.path("answer").asString("").strip();
            status = parseStatus(node.path("evidenceStatus").asString(""));
            node.path("unsupportedPoints").forEach(point -> {
                String value = point.asString("").strip();
                if (!value.isEmpty()) {
                    unsupported.add(value);
                }
            });
        } else {
            answer = ChatGateway.withoutThinking(raw);
            status = EvidenceStatus.PARTIAL;
            warnings.add("Câu trả lời của mô hình không đúng định dạng yêu cầu; hãy kiểm tra kỹ với nguồn.");
        }
        Map<String, String> sourceText = new LinkedHashMap<>();
        byLabel.forEach((label, hit) -> sourceText.put(label, hit.hit().content()));
        // Portfolio facts count as known context (like the question): restating them needs no citation.
        CitationValidator.Result validated = CitationValidator.validate(answer, status, sourceText, question + "\n" + facts);
        warnings.addAll(validated.warnings());
        unsupported.forEach(point -> warnings.add("Chưa có bằng chứng: " + point));
        List<Citation> citations = validated.citedLabels().stream()
                .map(label -> Citations.of(byLabel.get(label), general.analysis().terms()))
                .toList();
        LocalDate newest = evidence.stream().map(SearchHit::publishedOn).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        log.info("Portfolio question answered portfolio={} status={} retrieved={} cited={} warnings={}", portfolioId,
                validated.status(), evidence.size(), citations.size(), warnings.size());
        return new PortfolioAnswer(portfolioId, snapshot.summary().name(), snapshot.summary(), snapshot.holdings(), facts,
                profile.configured(), goals.size(), focusSymbols, validated.answer(), validated.status(), citations,
                evidence.stream().map(SearchHit::sourceId).distinct().toList(), List.copyOf(warnings), newest, settings.modelLabel(),
                IntelligenceViews.DISCLAIMER);
    }

    /**
     * Holdings to search individually: those the question names (by ticker or name); otherwise the largest
     * holdings by cost basis, capped by {@code max-holdings-in-context}.
     */
    List<InstrumentView> focus(String question, List<String> questionTickers, PortfolioSnapshot snapshot) {
        Map<UUID, InstrumentView> instruments = new LinkedHashMap<>();
        snapshot.instruments().forEach(instrument -> instruments.put(instrument.id(), instrument));
        String folded = " " + Keywords.normalize(question);
        List<InstrumentView> named = new ArrayList<>();
        for (HoldingView holding : snapshot.holdings()) {
            InstrumentView instrument = instruments.get(holding.instrumentId());
            if (instrument == null) {
                continue;
            }
            boolean byTicker = questionTickers.contains(instrument.symbol().toUpperCase(Locale.ROOT));
            boolean byName = ResearchMatcher.names(instrument).stream().map(Keywords::normalize)
                    .anyMatch(name -> folded.contains(" " + name));
            if (byTicker || byName) {
                named.add(instrument);
            }
        }
        if (!named.isEmpty()) {
            return named;
        }
        return snapshot.holdings().stream()
                .map(holding -> instruments.get(holding.instrumentId()))
                .filter(Objects::nonNull)
                .limit(properties.maxHoldingsInContext())
                .toList();
    }

    private static EvidenceStatus parseStatus(String value) {
        try {
            return EvidenceStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EvidenceStatus.PARTIAL;
        }
    }

    @Override
    public void destroy() {
        chat.close();
    }
}

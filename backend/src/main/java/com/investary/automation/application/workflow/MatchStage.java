package com.investary.automation.application.workflow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.investary.automation.WorkflowProperties;
import com.investary.automation.application.workflow.StageResult.Done;
import com.investary.automation.application.workflow.StageResult.NextJob;
import com.investary.automation.application.workflow.StageResult.StageUpdate;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore;
import com.investary.automation.persistence.WorkflowStore.MatchRow;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.document.domain.Document;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.intelligence.application.SourceRelevanceService;
import com.investary.intelligence.application.SourceRelevanceService.PortfolioRelevance;
import com.investary.intelligence.application.SourceRelevanceService.RelevantHolding;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.search.ChunkLookupService;
import com.investary.research.application.LinkedDocumentView;
import com.investary.research.application.ResearchItemView;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.extraction.ResearchItemType;

/**
 * Deterministic portfolio matching, before any AI call. Tickers come only from data already extracted from
 * the source: indexed chunk tickers, research items, research link ticker hints and document metadata.
 * They are compared with the symbols and aliases of open holdings ({@link SourceRelevanceService}); ETF
 * constituents are never inferred. Watchlists do not exist yet, so only holdings are matched.
 */
@Component
class MatchStage implements StageHandler {

    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<ResearchItemType> MARKET_WIDE = EnumSet.of(ResearchItemType.MARKET_UPDATE, ResearchItemType.MACRO_UPDATE);
    private static final int KEY_HASH_LENGTH = 16;

    private final WorkflowSources sources;
    private final ResearchQueryService research;
    private final ChunkLookupService chunks;
    private final SourceRelevanceService relevance;
    private final WorkflowStore store;
    private final WorkflowProperties properties;
    private final Clock clock;

    MatchStage(WorkflowSources sources, ResearchQueryService research, ChunkLookupService chunks, SourceRelevanceService relevance,
               WorkflowStore store, WorkflowProperties properties, Clock clock) {
        this.sources = sources;
        this.research = research;
        this.chunks = chunks;
        this.relevance = relevance;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.MATCH;
    }

    @Override
    public StageResult run(ClaimedJob job, WorkflowRow workflow) {
        Set<String> tickers = new TreeSet<>();
        boolean marketWide = false;
        LocalDate publishedOn;
        if (workflow.sourceType() == WorkflowSourceType.DOCUMENT) {
            Document document = sources.document(workflow.sourceId());
            add(tickers, document.getTicker());
            tickers.addAll(chunks.sourceTickers(KnowledgeSourceType.DOCUMENT, workflow.sourceId()));
            publishedOn = document.getPublishedOn();
        } else {
            EmailDetail email = sources.email(workflow.sourceId());
            tickers.addAll(chunks.sourceTickers(KnowledgeSourceType.EMAIL, workflow.sourceId()));
            for (LinkedDocumentView document : sources.linkedDocuments(workflow.sourceId())) {
                add(tickers, document.ticker());
                tickers.addAll(chunks.sourceTickers(KnowledgeSourceType.DOCUMENT, document.documentId()));
            }
            List<ResearchItemView> items = research.researchItems(workflow.sourceId());
            items.forEach(item -> add(tickers, item.ticker()));
            research.links(workflow.sourceId()).stream().filter(l -> l.category().isResearch()).forEach(l -> add(tickers, l.tickerHint()));
            marketWide = items.stream().anyMatch(item -> MARKET_WIDE.contains(item.itemType()));
            publishedOn = (email.sentAt() != null ? email.sentAt() : email.importedAt()).atZone(MARKET_ZONE).toLocalDate();
        }

        List<PortfolioRelevance> relevant = relevance.match(tickers, marketWide && properties.assessMarketContext());
        List<MatchRow> rows = new ArrayList<>();
        int holdings = 0;
        for (PortfolioRelevance portfolio : relevant) {
            for (RelevantHolding holding : portfolio.holdings()) {
                rows.add(new MatchRow(portfolio.portfolioId(), holding.instrumentId(), holding.symbol(), "DIRECT", publishedOn,
                        portfolio.snapshotHash(), clock.instant()));
                holdings++;
            }
            if (portfolio.holdings().isEmpty() && portfolio.marketContext()) {
                rows.add(new MatchRow(portfolio.portfolioId(), null, null, "MARKET_CONTEXT", publishedOn, portfolio.snapshotHash(),
                        clock.instant()));
            }
        }
        store.replaceMatches(workflow.id(), rows, clock.instant());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("tickers", tickers.size());
        counts.put("portfolios", relevant.size());
        counts.put("holdings", holdings);

        List<NextJob> next = new ArrayList<>();
        if (properties.autoAssess() && !relevant.isEmpty()) {
            String content = shortHash(sources.fingerprint(workflow));
            relevant.forEach(portfolio -> next.add(new NextJob(JobType.ASSESS, portfolio.portfolioId(),
                    shortHash(portfolio.snapshotHash()) + ":" + content)));
        }
        Done done = Done.of(StageStatus.COMPLETED, relevant.isEmpty() ? "NO_RELEVANT_HOLDINGS" : null, counts, next);
        if (next.isEmpty()) {
            String reason = relevant.isEmpty() ? "NO_RELEVANT_HOLDINGS" : "AUTO_ASSESS_DISABLED";
            done = done.withStages(List.of(new StageUpdate(WorkflowStage.IMPACT_ASSESSED, StageStatus.SKIPPED, reason, Map.of())));
        }
        return done;
    }

    private static void add(Set<String> tickers, String ticker) {
        if (ticker != null && !ticker.isBlank()) {
            tickers.add(ticker.strip().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static String shortHash(String hash) {
        return hash.length() <= KEY_HASH_LENGTH ? hash : hash.substring(0, KEY_HASH_LENGTH);
    }
}

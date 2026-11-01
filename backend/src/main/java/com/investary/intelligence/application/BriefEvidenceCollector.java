package com.investary.intelligence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.investary.intelligence.domain.ImpactAssessment;
import com.investary.intelligence.domain.ImpactAssessmentItem;
import com.investary.intelligence.domain.ImpactCategory;
import com.investary.intelligence.domain.Relevance;
import com.investary.intelligence.domain.ResearchState;
import com.investary.intelligence.persistence.ImpactAssessmentItemRepository;
import com.investary.intelligence.persistence.ImpactAssessmentRepository;
import com.investary.investor.application.GoalView;
import com.investary.investor.application.InvestorQueryService;
import com.investary.investor.application.ProfileView;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ChunkLookupService;
import com.investary.knowledge.search.ResearchAvailabilityService;
import com.investary.knowledge.search.ResearchAvailabilityService.AvailableSource;
import com.investary.knowledge.search.ResearchAvailabilityService.PendingSource;
import com.investary.knowledge.search.SearchHit;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.research.link.Keywords;

/**
 * Builds the bounded, deterministic evidence package of one brief: what became searchable in the period
 * (eligible), what arrived but is not searchable (pending), deduplicated and ranked by relevance to the
 * portfolio, with labelled chunks (S1..Sn) within the source and context limits. No model is involved.
 */
@Service
class BriefEvidenceCollector {

    private final ResearchAvailabilityService availability;
    private final ChunkLookupService chunkLookup;
    private final PortfolioQueryService portfolios;
    private final InvestorQueryService investor;
    private final ImpactAssessmentRepository assessments;
    private final ImpactAssessmentItemRepository assessmentItems;
    private final BriefProperties properties;

    BriefEvidenceCollector(ResearchAvailabilityService availability, ChunkLookupService chunkLookup, PortfolioQueryService portfolios,
                           InvestorQueryService investor, ImpactAssessmentRepository assessments,
                           ImpactAssessmentItemRepository assessmentItems, BriefProperties properties) {
        this.availability = availability;
        this.chunkLookup = chunkLookup;
        this.portfolios = portfolios;
        this.investor = investor;
        this.assessments = assessments;
        this.assessmentItems = assessmentItems;
        this.properties = properties;
    }

    BriefEvidence collect(UUID portfolioId, Instant start, Instant end, ZoneId zone, Instant cutoff) {
        PortfolioSnapshot snapshot = portfolioId == null ? null : portfolios.snapshot(portfolioId);
        List<HoldingView> holdings = snapshot == null ? List.of()
                : snapshot.holdings().stream().filter(h -> h.quantity() != null && h.quantity().signum() > 0).toList();
        Map<String, String> heldByTicker = heldByTicker(snapshot, holdings);

        List<AvailableSource> available = availability.available(start, end, cutoff);
        List<PendingSource> pending = availability.pending(start, end);

        // 1. Deduplicate: an email whose attached or linked PDF is also new is folded into that document.
        Set<UUID> newDocuments = new HashSet<>();
        available.stream().filter(s -> s.sourceType() == KnowledgeSourceType.DOCUMENT).forEach(s -> newDocuments.add(s.sourceId()));
        List<UUID> emails = available.stream().filter(s -> s.sourceType() == KnowledgeSourceType.EMAIL).map(AvailableSource::sourceId).toList();
        Map<UUID, List<UUID>> documentsOfEmails = availability.documentsOfEmails(emails);
        Map<UUID, List<UUID>> receivedVia = new HashMap<>();
        List<AvailableSource> distinct = new ArrayList<>();
        for (AvailableSource source : available) {
            if (source.sourceType() == KnowledgeSourceType.EMAIL) {
                List<UUID> folded = documentsOfEmails.getOrDefault(source.sourceId(), List.of()).stream().filter(newDocuments::contains).toList();
                if (!folded.isEmpty()) {
                    folded.forEach(doc -> receivedVia.computeIfAbsent(doc, id -> new ArrayList<>()).add(source.sourceId()));
                    continue;
                }
            }
            distinct.add(source);
        }

        // 2. Relevance, flags and ranking.
        LocalDate periodStartDate = start.atZone(zone).toLocalDate();
        List<Candidate> candidates = new ArrayList<>();
        for (AvailableSource source : distinct) {
            Set<String> tickers = new LinkedHashSet<>();
            source.tickers().forEach(t -> tickers.add(t.toUpperCase(Locale.ROOT)));
            if (source.sourceTicker() != null) {
                tickers.add(source.sourceTicker().strip().toUpperCase(Locale.ROOT));
            }
            List<String> held = tickers.stream().map(heldByTicker::get).filter(s -> s != null).distinct().toList();
            List<String> other = tickers.stream().filter(t -> !heldByTicker.containsKey(t)).toList();
            Relevance relevance = relevance(source, tickers, held, heldByTicker);
            boolean old = source.publishedOn() != null && source.publishedOn().isBefore(periodStartDate.minusDays(properties.oldPublicationDays()));
            boolean late = source.importedAt() != null && source.importedAt().isBefore(start);
            candidates.add(new Candidate(source, relevance, held, other, receivedVia.getOrDefault(source.sourceId(), List.of()), old, late));
        }
        candidates.sort(Comparator.comparing((Candidate c) -> c.relevance().ordinal())
                .thenComparing(c -> c.source().publishedOn(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> c.source().firstIndexedAt()));

        // 3. Labelled chunks within the source and context limits.
        List<Entry> selected = new ArrayList<>();
        List<Entry> omitted = new ArrayList<>();
        Set<String> seenText = new HashSet<>();
        int labels = 0;
        int budget = properties.maxContextChars();
        for (Candidate candidate : candidates) {
            if (selected.size() >= properties.maxSources()) {
                omitted.add(candidate.entry(List.of()));
                continue;
            }
            List<String> preferred = candidate.relevance() == Relevance.OTHER ? candidate.otherTickers() : candidate.heldSymbols();
            List<LabeledHit> hits = new ArrayList<>();
            for (SearchHit hit : availability.chunks(candidate.source().knowledgeSourceId(), preferred, properties.chunksPerSource())) {
                String normalized = hit.content().strip();
                if (!seenText.add(normalized)) {
                    continue; // identical text from another source is used once
                }
                if (labels > 0 && hit.content().length() > budget) {
                    continue;
                }
                budget -= hit.content().length();
                hits.add(new LabeledHit("S" + (++labels), hit));
            }
            (hits.isEmpty() ? omitted : selected).add(candidate.entry(hits));
        }
        Map<UUID, String> hashes = chunkLookup.currentHashes(selected.stream()
                .flatMap(e -> e.hits().stream()).map(h -> h.hit().chunkId()).toList());

        ResearchState state = !selected.isEmpty() || !omitted.isEmpty() ? ResearchState.NEW_RESEARCH
                : pending.isEmpty() ? ResearchState.NO_MATERIAL_RESEARCH : ResearchState.PENDING_ONLY;
        return new BriefEvidence(portfolioId, snapshot, holdings, selected, omitted, pending, hashes, investor.profile(), investor.goals(),
                assessments(portfolioId, start, cutoff), state);
    }

    /** Ticker or alias (upper case) → held symbol. */
    private static Map<String, String> heldByTicker(PortfolioSnapshot snapshot, List<HoldingView> holdings) {
        Map<String, String> result = new HashMap<>();
        if (snapshot == null) {
            return result;
        }
        Map<UUID, InstrumentView> instruments = new HashMap<>();
        snapshot.instruments().forEach(i -> instruments.put(i.id(), i));
        for (HoldingView holding : holdings) {
            result.put(holding.symbol().strip().toUpperCase(Locale.ROOT), holding.symbol());
            InstrumentView instrument = instruments.get(holding.instrumentId());
            if (instrument != null && instrument.aliases() != null) {
                instrument.aliases().stream().filter(a -> a != null && !a.isBlank())
                        .forEach(a -> result.putIfAbsent(a.strip().toUpperCase(Locale.ROOT), holding.symbol()));
            }
        }
        return result;
    }

    /**
     * DIRECT: the source's own ticker is held, it names only one ticker, or its title names the holding;
     * MENTION: a holding is one of several tickers; MARKET: no tickers; OTHER: only tickers that are not held.
     */
    static Relevance relevance(AvailableSource source, Set<String> tickers, List<String> held, Map<String, String> heldByTicker) {
        if (!held.isEmpty()) {
            String own = source.sourceTicker() == null ? null : source.sourceTicker().strip().toUpperCase(Locale.ROOT);
            if (own != null && heldByTicker.containsKey(own) || tickers.size() == 1) {
                return Relevance.DIRECT;
            }
            String title = source.title() == null ? "" : source.title();
            boolean titled = held.stream().anyMatch(symbol ->
                    Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(symbol.toUpperCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}])")
                            .matcher(title.toUpperCase(Locale.ROOT)).find());
            return titled ? Relevance.DIRECT : Relevance.MENTION;
        }
        return tickers.isEmpty() ? Relevance.MARKET : Relevance.OTHER;
    }

    /** Latest assessment item per symbol among assessments created in [start, cutoff]. */
    private Map<String, AssessmentRef> assessments(UUID portfolioId, Instant start, Instant cutoff) {
        Map<String, AssessmentRef> result = new LinkedHashMap<>();
        if (portfolioId == null) {
            return result;
        }
        for (ImpactAssessment assessment : assessments
                .findByPortfolioIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanEqualOrderByCreatedAtDesc(portfolioId, start, cutoff)) {
            for (ImpactAssessmentItem item : assessmentItems.findByAssessmentIdOrderByPositionAsc(assessment.getId())) {
                result.putIfAbsent(item.getSymbol(), new AssessmentRef(assessment.getId(), assessment.getCreatedAt(), item.getCategory(),
                        item.isAiInterpretation()));
            }
        }
        return result;
    }

    private record Candidate(AvailableSource source, Relevance relevance, List<String> heldSymbols, List<String> otherTickers,
                             List<UUID> receivedVia, boolean oldPublication, boolean lateArrival) {

        Entry entry(List<LabeledHit> hits) {
            return new Entry(source, relevance, heldSymbols, otherTickers, List.copyOf(hits), receivedVia, oldPublication, lateArrival);
        }
    }

    /**
     * One deduplicated new source.
     *
     * @param heldSymbols  held instruments it names (symbols as held)
     * @param otherTickers tickers it names that are not held
     * @param hits         labelled chunks sent as evidence (empty for omitted sources)
     */
    record Entry(AvailableSource source, Relevance relevance, List<String> heldSymbols, List<String> otherTickers, List<LabeledHit> hits,
                 List<UUID> receivedVia, boolean oldPublication, boolean lateArrival) {

        List<String> labels() {
            return hits.stream().map(LabeledHit::label).toList();
        }
    }

    record AssessmentRef(UUID assessmentId, Instant createdAt, ImpactCategory category, boolean aiInterpretation) {
    }

    /**
     * @param snapshot    null for a general brief
     * @param chunkHashes chunk id → current content hash of the labelled chunks
     * @param assessments symbol → latest impact assessment item created in the period
     */
    record BriefEvidence(UUID portfolioId, PortfolioSnapshot snapshot, List<HoldingView> holdings, List<Entry> entries, List<Entry> omitted,
                         List<PendingSource> pending, Map<UUID, String> chunkHashes, ProfileView profile, List<GoalView> goals,
                         Map<String, AssessmentRef> assessments, ResearchState state) {

        List<LabeledHit> labeled() {
            return entries.stream().flatMap(e -> e.hits().stream()).toList();
        }

        int newSources() {
            return entries.size() + omitted.size();
        }
    }

    /** Folded form of a symbol, for comparisons in text. */
    static String fold(String value) {
        return Keywords.normalize(value);
    }
}

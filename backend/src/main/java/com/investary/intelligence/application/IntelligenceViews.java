package com.investary.intelligence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.intelligence.application.ResearchMatcher.HoldingResearch;
import com.investary.intelligence.application.ResearchMatcher.MatchKind;
import com.investary.intelligence.application.ResearchMatcher.MatchedEvidence;
import com.investary.intelligence.domain.AiStatus;
import com.investary.intelligence.domain.ImpactCategory;
import com.investary.investor.application.ProfileView;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.Citation;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;

/**
 * Responses of the intelligence services. Portfolio facts (computed by the backend), source evidence
 * (stored chunks) and AI interpretation (validated model text) are always separate fields.
 */
public final class IntelligenceViews {

    public static final String DISCLAIMER = "Đây là tổng hợp nghiên cứu dựa trên tài liệu trong kho và dữ liệu danh mục bạn nhập, "
            + "không phải lời khuyên đầu tư hay tín hiệu giao dịch. Khuyến nghị của công ty chứng khoán là ý kiến của bên thứ ba. "
            + "Giá thị trường là giá bạn nhập thủ công và có thể đã cũ.";

    private static final int EXCERPT_LENGTH = 300;

    private IntelligenceViews() {
    }

    /**
     * A reference to one stored chunk, copied from the knowledge base (never from model output).
     *
     * @param label         {@code Sx} marker when the evidence was sent to the model, else {@code null}
     * @param contentSha256 hash of the chunk text when it was used; a different current hash makes an assessment stale
     * @param outdated      older than the freshness limit, or undated
     */
    public record EvidenceReference(String label, MatchKind matchKind, KnowledgeSourceType sourceType, UUID sourceId, String title,
                                    String publisher, LocalDate publishedAt, String dateBasis, String location, String excerpt,
                                    UUID chunkId, String contentSha256, boolean outdated) {

        static EvidenceReference of(MatchedEvidence evidence, String label, String contentSha256) {
            SearchHit hit = evidence.hit();
            String excerpt = hit.content().length() <= EXCERPT_LENGTH ? hit.content() : hit.content().substring(0, EXCERPT_LENGTH - 1) + "…";
            return new EvidenceReference(label, evidence.kind(), hit.sourceType(), hit.sourceId(), hit.title(), hit.brokerage(),
                    hit.publishedOn(), hit.dateBasis(), PromptBuilder.location(hit), excerpt, hit.chunkId(), contentSha256,
                    evidence.outdated());
        }

        static EvidenceReference of(Citation citation, MatchedEvidence evidence, String contentSha256) {
            return new EvidenceReference(citation.label(), evidence.kind(), citation.sourceType(), citation.sourceId(), citation.title(),
                    citation.publisher(), citation.publishedAt(), citation.dateBasis(), citation.location(), citation.excerpt(),
                    citation.chunkId(), contentSha256, evidence.outdated());
        }
    }

    public record HoldingResearchView(UUID instrumentId, String symbol, String name, List<EvidenceReference> direct,
                                      List<EvidenceReference> mentions, LocalDate newestEvidenceDate) {

        static HoldingResearchView of(HoldingResearch research) {
            return new HoldingResearchView(research.instrumentId(), research.symbol(), research.name(),
                    research.direct().stream().map(e -> EvidenceReference.of(e, null, null)).toList(),
                    research.mentions().stream().map(e -> EvidenceReference.of(e, null, null)).toList(),
                    research.newestEvidenceDate());
        }
    }

    /**
     * @param searchMode LEXICAL_ONLY when no embedding model is configured
     */
    public record RelevantResearch(UUID portfolioId, LocalDate today, int freshDays, SearchResult.Mode searchMode,
                                   List<HoldingResearchView> holdings, List<EvidenceReference> marketContext, String disclaimer) {
    }

    public enum Severity {
        INFO,
        ATTENTION
    }

    /**
     * A deterministic, neutral observation about the portfolio (never a recommendation).
     */
    public record Observation(String code, Severity severity, String symbol, String message) {
    }

    public record ResearchCoverage(UUID instrumentId, String symbol, int directCount, int mentionCount,
                                   LocalDate newestEvidenceDate, boolean outdated) {
    }

    public record PortfolioAnalysis(UUID portfolioId, PortfolioSummary summary, List<HoldingView> holdings, ProfileView profile,
                                    int goalCount, List<Observation> observations, List<ResearchCoverage> researchCoverage,
                                    SearchResult.Mode searchMode, String disclaimer) {
    }

    /**
     * @param deterministicCategory category from evidence matching alone
     * @param category              validated AI category, or the deterministic one
     * @param summary               AI interpretation (validated) or a deterministic description
     * @param aiInterpretation      whether {@code summary} and {@code category} come from the model
     */
    public record AssessmentItemView(UUID instrumentId, String symbol, ImpactCategory deterministicCategory, ImpactCategory category,
                                     String summary, boolean aiInterpretation, EvidenceStatus evidenceStatus,
                                     List<EvidenceReference> evidence) {
    }

    /**
     * Holdings and totals as they were when an assessment was made.
     */
    public record AssessmentSnapshot(String portfolioName, String currency, LocalDate valuationDate, String valuationStatus,
                                     String totalCostBasis, List<SnapshotHolding> holdings) {
    }

    public record SnapshotHolding(UUID instrumentId, String symbol, String quantity, String costBasis, String costWeightPct,
                                  String valuationStatus, String latestPrice, LocalDate priceAsOf) {
    }

    public enum StaleReason {
        /** Transactions changed the holdings since the assessment was made. */
        HOLDINGS_CHANGED,
        /** A cited chunk was deleted, reindexed with different text, or belongs to an outdated index. */
        EVIDENCE_CHANGED
    }

    public record AssessmentView(UUID id, UUID portfolioId, Instant createdAt, AiStatus aiStatus, String model,
                                 String overallSummary, EvidenceStatus overallEvidenceStatus, List<String> warnings,
                                 boolean stale, List<StaleReason> staleReasons, AssessmentSnapshot snapshot,
                                 List<AssessmentItemView> items, String disclaimer) {
    }

    public record AssessmentListItem(UUID id, Instant createdAt, AiStatus aiStatus, int itemCount, boolean stale,
                                     List<StaleReason> staleReasons) {
    }
}

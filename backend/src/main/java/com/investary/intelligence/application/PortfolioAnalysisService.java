package com.investary.intelligence.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.investary.intelligence.application.IntelligenceViews.HoldingResearchView;
import com.investary.intelligence.application.IntelligenceViews.Observation;
import com.investary.intelligence.application.IntelligenceViews.PortfolioAnalysis;
import com.investary.intelligence.application.IntelligenceViews.RelevantResearch;
import com.investary.intelligence.application.IntelligenceViews.ResearchCoverage;
import com.investary.intelligence.application.IntelligenceViews.Severity;
import com.investary.intelligence.application.ResearchMatcher.HoldingResearch;
import com.investary.intelligence.application.ResearchMatcher.MatchedEvidence;
import com.investary.intelligence.application.ResearchMatcher.PortfolioResearch;
import com.investary.intelligence.application.ResearchMatcher.ResearchFilters;
import com.investary.investor.application.InvestorQueryService;
import com.investary.investor.application.ProfileView;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;

/**
 * Deterministic portfolio analysis and research matching. No AI is involved: every observation is a
 * rule applied to stored data, worded neutrally, and never a recommendation.
 */
@Service
public class PortfolioAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAnalysisService.class);

    private final PortfolioQueryService portfolios;
    private final InvestorQueryService investor;
    private final ResearchMatcher matcher;
    private final IntelligenceProperties properties;

    public PortfolioAnalysisService(PortfolioQueryService portfolios, InvestorQueryService investor, ResearchMatcher matcher,
                                    IntelligenceProperties properties) {
        this.portfolios = portfolios;
        this.investor = investor;
        this.matcher = matcher;
        this.properties = properties;
    }

    public RelevantResearch relevantResearch(UUID portfolioId, ResearchFilters filters) {
        PortfolioSnapshot snapshot = portfolios.snapshot(portfolioId);
        PortfolioResearch research = matcher.match(snapshot.instruments(), filters, portfolios.today());
        log.info("Relevant research matched portfolio={} holdings={} marketContext={}", portfolioId, research.holdings().size(),
                research.marketContext().size());
        return new RelevantResearch(portfolioId, research.today(), research.freshDays(), research.mode(),
                research.holdings().stream().map(HoldingResearchView::of).toList(),
                research.marketContext().stream().map(e -> IntelligenceViews.EvidenceReference.of(e, null, null)).toList(),
                IntelligenceViews.DISCLAIMER);
    }

    public PortfolioAnalysis analyze(UUID portfolioId) {
        PortfolioSnapshot snapshot = portfolios.snapshot(portfolioId);
        ProfileView profile = investor.profile();
        int goals = investor.goals().size();
        PortfolioResearch research = matcher.match(snapshot.instruments(), ResearchFilters.NONE, portfolios.today());

        List<Observation> observations = new ArrayList<>();
        observations.addAll(valuation(snapshot.summary()));
        observations.addAll(concentration(snapshot.holdings()));
        observations.addAll(profileChecks(profile, snapshot.holdings()));
        if (goals == 0) {
            observations.add(new Observation("NO_GOALS", Severity.INFO, null,
                    "Bạn chưa khai báo mục tiêu đầu tư; trợ lý không thể liên hệ danh mục với mục tiêu."));
        }
        List<ResearchCoverage> coverage = new ArrayList<>();
        for (HoldingResearch holding : research.holdings()) {
            boolean outdated = holding.all().stream().allMatch(MatchedEvidence::outdated);
            coverage.add(new ResearchCoverage(holding.instrumentId(), holding.symbol(), holding.direct().size(),
                    holding.mentions().size(), holding.newestEvidenceDate(), outdated));
            if (!holding.hasEvidence()) {
                observations.add(new Observation("NO_RESEARCH", Severity.ATTENTION, holding.symbol(),
                        "Kho tài liệu chưa có nghiên cứu nào nhắc tới " + holding.symbol() + "."));
            } else if (outdated) {
                observations.add(new Observation("OUTDATED_RESEARCH", Severity.ATTENTION, holding.symbol(),
                        "Nghiên cứu mới nhất về " + holding.symbol() + " (" + holding.newestEvidenceDate() + ") cũ hơn "
                                + properties.researchFreshDays() + " ngày."));
            }
        }
        log.info("Portfolio analysed portfolio={} holdings={} observations={}", portfolioId, snapshot.holdings().size(),
                observations.size());
        return new PortfolioAnalysis(portfolioId, snapshot.summary(), snapshot.holdings(), profile, goals, observations, coverage,
                research.mode(), IntelligenceViews.DISCLAIMER);
    }

    private static List<Observation> valuation(PortfolioSummary summary) {
        if (summary.openHoldings() == 0) {
            return List.of(new Observation("NO_HOLDINGS", Severity.INFO, null, "Danh mục chưa có vị thế mở."));
        }
        return switch (summary.valuationStatus()) {
            case AVAILABLE -> List.of();
            case STALE -> List.of(new Observation("VALUATION_STALE", Severity.ATTENTION, null,
                    summary.stalePricedHoldings() + " mã có giá cũ hơn " + summary.priceStaleAfterDays()
                            + " ngày; giá trị thị trường có thể không còn đúng."));
            case PARTIAL -> List.of(new Observation("VALUATION_PARTIAL", Severity.ATTENTION, null,
                    "Chỉ " + summary.pricedHoldings() + "/" + summary.openHoldings()
                            + " mã có giá; tổng giá trị thị trường và lãi/lỗ chưa thực hiện không được tính."));
            case UNAVAILABLE -> List.of(new Observation("VALUATION_UNAVAILABLE", Severity.ATTENTION, null,
                    "Chưa có giá cho mã nào; chỉ có số liệu theo giá vốn."));
        };
    }

    private List<Observation> concentration(List<HoldingView> holdings) {
        BigDecimal threshold = BigDecimal.valueOf(properties.concentrationThresholdPct());
        return holdings.stream()
                .filter(holding -> holding.costWeightPct() != null && holding.costWeightPct().compareTo(threshold) > 0)
                .map(holding -> new Observation("CONCENTRATION", Severity.ATTENTION, holding.symbol(),
                        holding.symbol() + " chiếm " + holding.costWeightPct().stripTrailingZeros().toPlainString()
                                + "% tổng giá vốn, trên ngưỡng tham chiếu " + threshold + "%."))
                .toList();
    }

    private static List<Observation> profileChecks(ProfileView profile, List<HoldingView> holdings) {
        List<Observation> result = new ArrayList<>();
        if (!profile.configured()) {
            result.add(new Observation("PROFILE_MISSING", Severity.INFO, null,
                    "Bạn chưa khai báo hồ sơ nhà đầu tư; không thể đối chiếu danh mục với khẩu vị rủi ro."));
            return result;
        }
        Set<String> preferred = profile.preferredInstruments().stream()
                .map(value -> value.strip().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        for (HoldingView holding : holdings) {
            if (!preferred.isEmpty() && !preferred.contains(holding.instrumentType().name())) {
                result.add(new Observation("TYPE_NOT_PREFERRED", Severity.INFO, holding.symbol(),
                        holding.symbol() + " thuộc loại " + holding.instrumentType() + ", không nằm trong loại tài sản ưa thích bạn khai báo ("
                                + String.join(", ", profile.preferredInstruments()) + ")."));
            }
            if (profile.restrictions() != null && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(holding.symbol()) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE).matcher(profile.restrictions()).find()) {
                result.add(new Observation("RESTRICTION_MENTIONS_HOLDING", Severity.ATTENTION, holding.symbol(),
                        holding.symbol() + " được nhắc tới trong mục hạn chế đầu tư bạn khai báo."));
            }
        }
        return result;
    }
}

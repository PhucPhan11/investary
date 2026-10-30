package com.investary.intelligence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.search.QueryAnalysis;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;
import com.investary.portfolio.application.PortfolioViews.PortfolioView;
import com.investary.portfolio.application.PortfolioViews.ValuationStatus;
import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;

/**
 * Synthetic portfolio and evidence values for intelligence unit tests. Figures are invented.
 */
public final class IntelligenceTestData {

    public static final LocalDate TODAY = LocalDate.parse("2026-10-30");
    public static final UUID PORTFOLIO = new UUID(7, 7);
    public static final UUID FPT = new UUID(1, 1);
    public static final UUID SCS = new UUID(1, 2);
    public static final UUID VNM = new UUID(1, 3);

    private IntelligenceTestData() {
    }

    public static InstrumentView instrument(UUID id, String symbol, String name, String... aliases) {
        return new InstrumentView(id, symbol, name, InstrumentType.STOCK, Exchange.HOSE, "VND", List.of(aliases));
    }

    public static HoldingView holding(UUID id, String symbol, String quantity, String cost, String weight) {
        return new HoldingView(id, symbol, symbol + " name", InstrumentType.STOCK, Exchange.HOSE, new BigDecimal(quantity),
                new BigDecimal(cost).divide(new BigDecimal(quantity), java.math.MathContext.DECIMAL64), new BigDecimal(cost), BigDecimal.ZERO, TODAY.minusDays(60),
                TODAY.minusDays(60), new BigDecimal(weight), ValuationStatus.UNAVAILABLE, null, null, null, null, null, null, null, null);
    }

    /** FPT 60%, SCS 30%, VNM 10% by cost basis; no prices. */
    public static PortfolioSnapshot snapshot() {
        List<HoldingView> holdings = List.of(
                holding(FPT, "FPT", "600", "60000000", "60.00"),
                holding(SCS, "SCS", "500", "30000000", "30.00"),
                holding(VNM, "VNM", "150", "10000000", "10.00"));
        PortfolioSummary summary = new PortfolioSummary(PORTFOLIO, "Danh mục thử", "VND", 3, 0, 3, new BigDecimal("100000000"),
                BigDecimal.ZERO, ValuationStatus.UNAVAILABLE, 0, 0, BigDecimal.ZERO, null, null, null, null, null, null, TODAY, null, 7,
                List.of());
        return new PortfolioSnapshot(new PortfolioView(PORTFOLIO, "Danh mục thử", null, "VND", 3, Instant.EPOCH, Instant.EPOCH), summary,
                holdings, List.of(instrument(FPT, "FPT", "Công ty Cổ phần FPT"), instrument(SCS, "SCS", "Dịch vụ Hàng hóa Sài Gòn"),
                        instrument(VNM, "VNM", "Vinamilk", "Sữa Việt Nam")));
    }

    public static SearchHit hit(String title, String content, List<String> tickers, String sourceTicker, LocalDate published, String basis) {
        return new SearchHit(UUID.randomUUID(), UUID.randomUUID(), KnowledgeSourceType.DOCUMENT, UUID.randomUUID(), title, "SSI",
                published, basis, 1, 1, null, 0, content, tickers, sourceTicker, null, null, null, 0.8, 0.03,
                EnumSet.of(SearchHit.MatchType.LEXICAL));
    }

    public static SearchResult result(List<SearchHit> hits) {
        return new SearchResult(hits, SearchResult.Mode.HYBRID, new QueryAnalysis(List.of(), List.of(), List.of()), List.of());
    }

    public static SearchResult result(List<SearchHit> hits, List<String> questionTickers) {
        return new SearchResult(hits, SearchResult.Mode.HYBRID, new QueryAnalysis(questionTickers, List.of(), List.of()), List.of());
    }

    public static ObjectProvider<ChatModel> provider(ChatModel model) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (model != null) {
            factory.addBean("chat", model);
        }
        return factory.getBeanProvider(ChatModel.class);
    }
}

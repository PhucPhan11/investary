package com.investary.research.link;

public enum LinkCategory {
    RESEARCH_REPORT,
    STOCK_RESEARCH,
    MARKET_REPORT,
    NAVIGATION,
    PROMOTION,
    OTHER;

    public boolean isResearch() {
        return this == RESEARCH_REPORT || this == STOCK_RESEARCH || this == MARKET_REPORT;
    }
}

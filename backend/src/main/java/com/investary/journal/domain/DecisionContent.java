package com.investary.journal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The user-written part of a decision. {@code portfolioName} and {@code symbol} are copies kept in case the
 * portfolio or instrument is deleted later. Intended quantity, allocation and price are intentions only; they are
 * never orders and never enter a calculation.
 */
public record DecisionContent(UUID portfolioId, String portfolioName, UUID instrumentId, String symbol, DecisionType decisionType,
                              LocalDate decisionDate, String title, String rationale, String expectedOutcome, String timeHorizon,
                              String mainAssumptions, String risks, String invalidationConditions, LocalDate reviewDate,
                              String notes, BigDecimal intendedQuantity, BigDecimal intendedAllocationPct, BigDecimal intendedPrice) {

    /** Fields that describe what the snapshot captured; fixed once the decision is finalized. */
    public static final Set<String> LOCKED_AFTER_FINALIZE = Set.of("portfolioId", "instrumentId", "decisionType", "decisionDate",
            "intendedQuantity", "intendedAllocationPct", "intendedPrice");

    /** A content with every field empty, the starting point of the first revision diff. */
    public static DecisionContent empty() {
        return new DecisionContent(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Field name to value, in a stable order, for diffs. The copies (portfolio name, symbol) are left out. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("portfolioId", portfolioId);
        map.put("instrumentId", instrumentId);
        map.put("decisionType", decisionType);
        map.put("decisionDate", decisionDate);
        map.put("title", title);
        map.put("rationale", rationale);
        map.put("expectedOutcome", expectedOutcome);
        map.put("timeHorizon", timeHorizon);
        map.put("mainAssumptions", mainAssumptions);
        map.put("risks", risks);
        map.put("invalidationConditions", invalidationConditions);
        map.put("reviewDate", reviewDate);
        map.put("notes", notes);
        map.put("intendedQuantity", intendedQuantity);
        map.put("intendedAllocationPct", intendedAllocationPct);
        map.put("intendedPrice", intendedPrice);
        return map;
    }
}

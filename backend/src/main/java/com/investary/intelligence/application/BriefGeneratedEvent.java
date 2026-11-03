package com.investary.intelligence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.ResearchState;

/**
 * Published after a new brief revision was committed (not for unchanged inputs). Carries only deterministic
 * facts: the conflicts are the explicit-rating conflicts found by {@code BriefConflicts}, never model output.
 *
 * @param portfolioId null for a general brief
 */
public record BriefGeneratedEvent(UUID briefId, UUID rootBriefId, int revision, UUID portfolioId, BriefTrigger trigger, BriefStatus status,
                                  ResearchState researchState, Instant periodEnd, LocalDate businessDate, List<Conflict> conflicts) {

    /**
     * @param held       the symbol is a holding of the brief's portfolio
     * @param sourceIds  emails or documents whose ratings differ
     * @param brokerages brokerages of those ratings
     */
    public record Conflict(String symbol, boolean held, List<UUID> sourceIds, List<String> brokerages) {
    }
}

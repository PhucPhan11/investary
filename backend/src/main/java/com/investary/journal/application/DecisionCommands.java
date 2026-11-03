package com.investary.journal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.investary.journal.domain.DecisionType;
import com.investary.journal.domain.EvidenceKind;
import com.investary.journal.domain.EvidenceRole;

/**
 * Inputs of the journal's write operations. Every write except create carries the version the user saw.
 */
public final class DecisionCommands {

    private DecisionCommands() {
    }

    /**
     * Full content of a decision. On an update of a finalized decision, {@code amendmentReason} is required and the
     * locked fields must be unchanged.
     */
    public record DecisionCommand(UUID portfolioId, UUID instrumentId, DecisionType decisionType, LocalDate decisionDate,
                                  String title, String rationale, String expectedOutcome, String timeHorizon,
                                  String mainAssumptions, String risks, String invalidationConditions, LocalDate reviewDate,
                                  String notes, BigDecimal intendedQuantity, BigDecimal intendedAllocationPct,
                                  BigDecimal intendedPrice, Integer expectedVersion, String amendmentReason) {
    }

    public record TransitionCommand(int expectedVersion, String reason) {
    }

    public record EvidenceCommand(EvidenceKind kind, UUID sourceId, EvidenceRole role, String note, int expectedVersion) {
    }

    public record LinkCommand(UUID transactionId, String note, int expectedVersion) {
    }
}

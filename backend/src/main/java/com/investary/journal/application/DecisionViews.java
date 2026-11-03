package com.investary.journal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.journal.domain.DecisionStatus;
import com.investary.journal.domain.DecisionType;
import com.investary.journal.domain.EvidenceKind;
import com.investary.journal.domain.EvidenceRole;
import com.investary.journal.domain.RevisionKind;
import com.investary.portfolio.application.PortfolioViews.TransactionView;

import tools.jackson.databind.JsonNode;

/**
 * Read models of the decision journal.
 */
public final class DecisionViews {

    /** Shown with every decision: the journal records the user's own decisions. */
    public static final String DISCLAIMER = "This journal records your own decisions and reasoning. It is not investment advice, "
            + "and brokerage ratings in attached evidence are the brokerage's statements.";

    private DecisionViews() {
    }

    public enum DecisionAction {
        EDIT,
        AMEND,
        FINALIZE,
        EXECUTE,
        CANCEL,
        ATTACH_EVIDENCE,
        LINK_TRANSACTION
    }

    public record DecisionSummary(UUID id, UUID portfolioId, String portfolioName, UUID instrumentId, String symbol,
                                  DecisionType decisionType, DecisionStatus status, LocalDate decisionDate, String title,
                                  LocalDate reviewDate, int version, Instant createdAt, Instant updatedAt) {
    }

    /**
     * @param portfolioId {@code null} for a watchlist decision or once the portfolio was deleted ({@code portfolioName} stays)
     * @param snapshot    {@code null} until the decision is finalized
     * @param actions     what the current status allows
     */
    public record DecisionDetail(UUID id, UUID portfolioId, String portfolioName, UUID instrumentId, String symbol,
                                 DecisionType decisionType, DecisionStatus status, LocalDate decisionDate, String title,
                                 String rationale, String expectedOutcome, String timeHorizon, String mainAssumptions, String risks,
                                 String invalidationConditions, LocalDate reviewDate, String notes, BigDecimal intendedQuantity,
                                 BigDecimal intendedAllocationPct, BigDecimal intendedPrice, Instant finalizedAt, Instant executedAt,
                                 Instant cancelledAt, String cancelReason, int version, Instant createdAt, Instant updatedAt,
                                 DecisionSnapshot snapshot, List<EvidenceView> evidence, List<TransactionLinkView> transactions,
                                 List<DecisionAction> actions, String disclaimer) {
    }

    /**
     * @param available false once the source was deleted (or, for a citation, its chunk is no longer current); the
     *                  copied fields still show what was attached, and no other source is substituted
     */
    public record EvidenceView(UUID id, EvidenceKind kind, EvidenceRole role, UUID sourceId, boolean available, String title,
                               String publisher, LocalDate publishedOn, String location, String excerpt, String note,
                               Instant attachedAt, Instant removedAt, String removedReason) {
    }

    public enum LinkState {
        /** The transaction exists with the facts that were linked. */
        CURRENT,
        /** The transaction exists but its type, date, quantity or price was edited after linking. */
        CHANGED,
        /** The transaction was deleted. */
        DELETED
    }

    /**
     * @param transactionId the linked transaction's id, kept after it is deleted
     * @param current       the transaction as it is now, {@code null} if deleted
     */
    public record TransactionLinkView(UUID id, UUID transactionId, UUID portfolioId, String symbol, String transactionType,
                                      LocalDate tradeDate, BigDecimal quantity, BigDecimal price, String note, Instant linkedAt,
                                      Instant removedAt, String removedReason, LinkState state, TransactionView current) {
    }

    public record RevisionView(int sequence, RevisionKind kind, String reason, DecisionStatus statusAfter, JsonNode changes,
                               Instant createdAt) {
    }
}

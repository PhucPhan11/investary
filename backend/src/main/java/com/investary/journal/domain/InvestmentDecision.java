package com.investary.journal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A journaled investment decision. Status changes go only through {@link #finalizeDecision}, {@link #execute} and
 * {@link #cancel}, which refuse any other transition. {@code version} is the optimistic lock.
 */
@Entity
@Table(name = "investment_decisions")
public class InvestmentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "portfolio_name")
    private String portfolioName;

    @Column(name = "instrument_id")
    private UUID instrumentId;

    @Column(name = "symbol")
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false)
    private DecisionType decisionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DecisionStatus status;

    @Column(name = "decision_date", nullable = false)
    private LocalDate decisionDate;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "rationale")
    private String rationale;

    @Column(name = "expected_outcome")
    private String expectedOutcome;

    @Column(name = "time_horizon")
    private String timeHorizon;

    @Column(name = "main_assumptions")
    private String mainAssumptions;

    @Column(name = "risks")
    private String risks;

    @Column(name = "invalidation_conditions")
    private String invalidationConditions;

    @Column(name = "review_date")
    private LocalDate reviewDate;

    @Column(name = "notes")
    private String notes;

    @Column(name = "intended_quantity")
    private BigDecimal intendedQuantity;

    @Column(name = "intended_allocation_pct")
    private BigDecimal intendedAllocationPct;

    @Column(name = "intended_price")
    private BigDecimal intendedPrice;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvestmentDecision() {
        // for JPA
    }

    public InvestmentDecision(DecisionContent content, Instant now) {
        this.status = DecisionStatus.DRAFT;
        this.createdAt = now;
        apply(content, now);
    }

    public DecisionContent content() {
        return new DecisionContent(portfolioId, portfolioName, instrumentId, symbol, decisionType, decisionDate, title, rationale,
                expectedOutcome, timeHorizon, mainAssumptions, risks, invalidationConditions, reviewDate, notes, intendedQuantity,
                intendedAllocationPct, intendedPrice);
    }

    /** Replaces the content; the service decides which fields may change in the current status. */
    public void apply(DecisionContent content, Instant now) {
        this.portfolioId = content.portfolioId();
        this.portfolioName = content.portfolioName();
        this.instrumentId = content.instrumentId();
        this.symbol = content.symbol();
        this.decisionType = content.decisionType();
        this.decisionDate = content.decisionDate();
        this.title = content.title();
        this.rationale = content.rationale();
        this.expectedOutcome = content.expectedOutcome();
        this.timeHorizon = content.timeHorizon();
        this.mainAssumptions = content.mainAssumptions();
        this.risks = content.risks();
        this.invalidationConditions = content.invalidationConditions();
        this.reviewDate = content.reviewDate();
        this.notes = content.notes();
        this.intendedQuantity = content.intendedQuantity();
        this.intendedAllocationPct = content.intendedAllocationPct();
        this.intendedPrice = content.intendedPrice();
        this.updatedAt = now;
    }

    /** @return false if the decision is not a draft */
    public boolean finalizeDecision(Instant now) {
        if (status != DecisionStatus.DRAFT) {
            return false;
        }
        status = DecisionStatus.DECIDED;
        finalizedAt = now;
        updatedAt = now;
        return true;
    }

    /** @return false if the decision is not DECIDED */
    public boolean execute(Instant now) {
        if (status != DecisionStatus.DECIDED) {
            return false;
        }
        status = DecisionStatus.EXECUTED;
        executedAt = now;
        updatedAt = now;
        return true;
    }

    /** @return false if the decision is already EXECUTED or CANCELLED */
    public boolean cancel(String reason, Instant now) {
        if (status != DecisionStatus.DRAFT && status != DecisionStatus.DECIDED) {
            return false;
        }
        status = DecisionStatus.CANCELLED;
        cancelledAt = now;
        cancelReason = reason;
        updatedAt = now;
        return true;
    }

    /** Records a change to evidence or links, so the version moves and concurrent writers conflict. */
    public void touch(Instant now) {
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getPortfolioName() {
        return portfolioName;
    }

    public UUID getInstrumentId() {
        return instrumentId;
    }

    public String getSymbol() {
        return symbol;
    }

    public DecisionType getDecisionType() {
        return decisionType;
    }

    public DecisionStatus getStatus() {
        return status;
    }

    public LocalDate getDecisionDate() {
        return decisionDate;
    }

    public String getTitle() {
        return title;
    }

    public String getRationale() {
        return rationale;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.investary.journal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Links an existing portfolio transaction to a decision, with the trade facts copied at link time. The
 * transaction stays the financial source of truth; these copies only show what was linked. {@code transactionId}
 * becomes {@code null} when the transaction is deleted; {@code transactionRef} keeps its id.
 */
@Entity
@Table(name = "decision_transaction_links")
public class DecisionTransactionLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "transaction_ref", nullable = false, updatable = false)
    private UUID transactionRef;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "symbol", nullable = false, updatable = false)
    private String symbol;

    @Column(name = "transaction_type", nullable = false, updatable = false)
    private String transactionType;

    @Column(name = "trade_date", nullable = false, updatable = false)
    private LocalDate tradeDate;

    @Column(name = "quantity", nullable = false, updatable = false)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false, updatable = false)
    private BigDecimal price;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_reason")
    private String removedReason;

    protected DecisionTransactionLink() {
        // for JPA
    }

    public DecisionTransactionLink(UUID decisionId, UUID transactionId, UUID portfolioId, String symbol, String transactionType,
                                   LocalDate tradeDate, BigDecimal quantity, BigDecimal price, String note, Instant linkedAt) {
        this.decisionId = decisionId;
        this.transactionId = transactionId;
        this.transactionRef = transactionId;
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.transactionType = transactionType;
        this.tradeDate = tradeDate;
        this.quantity = quantity;
        this.price = price;
        this.note = note;
        this.linkedAt = linkedAt;
    }

    public void remove(String reason, Instant now) {
        this.removedAt = now;
        this.removedReason = reason;
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getTransactionRef() {
        return transactionRef;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getNote() {
        return note;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public String getRemovedReason() {
        return removedReason;
    }
}

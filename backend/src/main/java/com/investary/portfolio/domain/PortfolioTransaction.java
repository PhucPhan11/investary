package com.investary.portfolio.domain;

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

/**
 * A user-entered trade. Amounts are in the portfolio's base currency. Transactions on the same trade
 * date are applied in the order they were created.
 */
@Entity
@Table(name = "portfolio_transactions")
public class PortfolioTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "instrument_id", nullable = false)
    private UUID instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType type;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "fees", nullable = false)
    private BigDecimal fees;

    @Column(name = "taxes", nullable = false)
    private BigDecimal taxes;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PortfolioTransaction() {
        // for JPA
    }

    public PortfolioTransaction(UUID portfolioId, Instant now) {
        this.portfolioId = portfolioId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(UUID instrumentId, TransactionType type, LocalDate tradeDate, BigDecimal quantity, BigDecimal price,
                       BigDecimal fees, BigDecimal taxes, String notes, Instant now) {
        this.instrumentId = instrumentId;
        this.type = type;
        this.tradeDate = tradeDate;
        this.quantity = quantity;
        this.price = price;
        this.fees = fees;
        this.taxes = taxes;
        this.notes = notes;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getInstrumentId() {
        return instrumentId;
    }

    public TransactionType getType() {
        return type;
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

    public BigDecimal getFees() {
        return fees;
    }

    public BigDecimal getTaxes() {
        return taxes;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.investary.portfolio.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A named collection of transactions. Holdings are always computed from the transactions, never stored.
 */
@Entity
@Table(name = "portfolios")
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "base_currency", nullable = false, updatable = false)
    private String baseCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Portfolio() {
        // for JPA
    }

    public Portfolio(String name, String description, String baseCurrency, Instant now) {
        this.name = name;
        this.description = description;
        this.baseCurrency = baseCurrency;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }

    /** Records that the portfolio's transactions changed. */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

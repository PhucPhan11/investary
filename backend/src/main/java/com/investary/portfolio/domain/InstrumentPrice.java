package com.investary.portfolio.domain;

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
 * A manually entered price or NAV observation. Investary has no market data feed: {@code source} says
 * where the user read the value (e.g. "Bảng giá HOSE", "NAV công bố của quỹ").
 */
@Entity
@Table(name = "instrument_prices")
public class InstrumentPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instrument_id", nullable = false, updatable = false)
    private UUID instrumentId;

    @Column(name = "price", nullable = false, updatable = false)
    private BigDecimal price;

    @Column(name = "as_of", nullable = false, updatable = false)
    private LocalDate asOf;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InstrumentPrice() {
        // for JPA
    }

    public InstrumentPrice(UUID instrumentId, BigDecimal price, LocalDate asOf, String source, String note, Instant createdAt) {
        this.instrumentId = instrumentId;
        this.price = price;
        this.asOf = asOf;
        this.source = source;
        this.note = note;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstrumentId() {
        return instrumentId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    public String getSource() {
        return source;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.investary.portfolio.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A security the user holds or tracks (Vietnamese stock, ETF, fund, bond or any manual instrument).
 * Entered by the user; there is no exchange master list. {@code aliases} are extra names used to match
 * research that mentions the company by name instead of ticker.
 */
@Entity
@Table(name = "instruments")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false)
    private InstrumentType instrumentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange")
    private Exchange exchange;

    @Column(name = "currency", nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "aliases", nullable = false, columnDefinition = "text[]")
    private String[] aliases = new String[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Instrument() {
        // for JPA
    }

    public Instrument(Instant now) {
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String symbol, String name, InstrumentType instrumentType, Exchange exchange, String currency,
                       List<String> aliases, Instant now) {
        this.symbol = symbol;
        this.name = name;
        this.instrumentType = instrumentType;
        this.exchange = exchange;
        this.currency = currency;
        this.aliases = aliases.toArray(String[]::new);
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public Exchange getExchange() {
        return exchange;
    }

    public String getCurrency() {
        return currency;
    }

    public List<String> getAliases() {
        return aliases == null ? List.of() : List.of(aliases);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.investary.investor.domain;

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
 * The single user's self-declared investor profile. Every field is optional: Investary never infers
 * profile values, it only records what the user entered.
 */
@Entity
@Table(name = "investor_profile")
public class InvestorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level")
    private ExperienceLevel experienceLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tolerance")
    private RiskTolerance riskTolerance;

    @Column(name = "objectives")
    private String objectives;

    @Enumerated(EnumType.STRING)
    @Column(name = "investment_horizon")
    private InvestmentHorizon investmentHorizon;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "preferred_instruments", nullable = false, columnDefinition = "text[]")
    private String[] preferredInstruments = new String[0];

    @Column(name = "restrictions")
    private String restrictions;

    @Column(name = "liquidity_needs")
    private String liquidityNeeds;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvestorProfile() {
        // for JPA
    }

    public InvestorProfile(Instant now) {
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(ExperienceLevel experienceLevel, RiskTolerance riskTolerance, String objectives,
                       InvestmentHorizon investmentHorizon, List<String> preferredInstruments, String restrictions,
                       String liquidityNeeds, String notes, Instant now) {
        this.experienceLevel = experienceLevel;
        this.riskTolerance = riskTolerance;
        this.objectives = objectives;
        this.investmentHorizon = investmentHorizon;
        this.preferredInstruments = preferredInstruments.toArray(String[]::new);
        this.restrictions = restrictions;
        this.liquidityNeeds = liquidityNeeds;
        this.notes = notes;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public ExperienceLevel getExperienceLevel() {
        return experienceLevel;
    }

    public RiskTolerance getRiskTolerance() {
        return riskTolerance;
    }

    public String getObjectives() {
        return objectives;
    }

    public InvestmentHorizon getInvestmentHorizon() {
        return investmentHorizon;
    }

    public List<String> getPreferredInstruments() {
        return preferredInstruments == null ? List.of() : List.of(preferredInstruments);
    }

    public String getRestrictions() {
        return restrictions;
    }

    public String getLiquidityNeeds() {
        return liquidityNeeds;
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

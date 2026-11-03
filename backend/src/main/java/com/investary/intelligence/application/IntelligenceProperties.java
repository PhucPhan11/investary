package com.investary.intelligence.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/**
 * Portfolio intelligence settings ({@code investary.intelligence.*}).
 *
 * @param researchFreshDays         evidence older than this (by publication date) is flagged as outdated
 * @param concentrationThresholdPct cost-basis weight above which a holding is reported as concentrated (informational)
 * @param maxHoldingsInContext      holdings searched individually for a portfolio-aware question
 * @param evidencePerHolding        evidence chunks kept per holding for assessments and questions
 * @param marketContextResults      market-context chunks kept
 */
@Validated
@ConfigurationProperties("investary.intelligence")
public record IntelligenceProperties(@Positive int researchFreshDays, @Positive @Max(100) int concentrationThresholdPct,
                                     @Positive int maxHoldingsInContext, @Positive int evidencePerHolding,
                                     @Positive int marketContextResults) {
}

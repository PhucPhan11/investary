package com.investary.portfolio.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Portfolio settings ({@code investary.portfolio.*}).
 *
 * @param priceStaleAfterDays a price observation older than this many days marks the holding's valuation STALE
 */
@Validated
@ConfigurationProperties("investary.portfolio")
public record PortfolioProperties(@Positive int priceStaleAfterDays) {
}

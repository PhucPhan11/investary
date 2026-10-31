package com.investary.research.application;

import java.time.Instant;
import java.util.UUID;

import com.investary.research.extraction.ResearchItemType;

import tools.jackson.databind.JsonNode;

public record ResearchItemView(
        UUID id,
        ResearchItemType itemType,
        String ticker,
        String brokerage,
        Instant publishedAt,
        String title,
        String content,
        JsonNode structuredData,
        String extractionMethod,
        String sourceLocation,
        String language) {
}

package com.investary.intelligence.application;

import java.util.List;
import java.util.UUID;

import com.investary.intelligence.domain.ImpactCategory;
import com.investary.knowledge.assistant.EvidenceStatus;

/**
 * Published after an impact assessment was committed. Items carry the validated category and evidence status
 * only, never the model's wording.
 *
 * @param triggerSourceType EMAIL or DOCUMENT for an assessment started by automation; null for a manual one
 */
public record ImpactAssessmentCreatedEvent(UUID assessmentId, UUID portfolioId, String triggerSourceType, UUID triggerSourceId,
                                           List<Item> items) {

    public record Item(UUID instrumentId, String symbol, ImpactCategory deterministicCategory, ImpactCategory category,
                       EvidenceStatus evidenceStatus) {
    }
}

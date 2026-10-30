package com.investary.intelligence.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.intelligence.domain.ImpactAssessmentItem;

public interface ImpactAssessmentItemRepository extends JpaRepository<ImpactAssessmentItem, UUID> {

    List<ImpactAssessmentItem> findByAssessmentIdOrderByPositionAsc(UUID assessmentId);
}

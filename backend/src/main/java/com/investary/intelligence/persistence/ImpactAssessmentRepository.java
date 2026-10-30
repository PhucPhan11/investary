package com.investary.intelligence.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.intelligence.domain.ImpactAssessment;

public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, UUID> {

    List<ImpactAssessment> findTop20ByPortfolioIdOrderByCreatedAtDesc(UUID portfolioId);
}

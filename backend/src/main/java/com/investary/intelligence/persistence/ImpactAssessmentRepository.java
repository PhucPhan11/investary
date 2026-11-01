package com.investary.intelligence.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.intelligence.domain.ImpactAssessment;

public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, UUID> {

    List<ImpactAssessment> findTop20ByPortfolioIdOrderByCreatedAtDesc(UUID portfolioId);

    Optional<ImpactAssessment> findFirstByPortfolioIdAndTriggerSourceIdAndSnapshotHashAndTriggerContentSha256(
            UUID portfolioId, UUID triggerSourceId, String snapshotHash, String triggerContentSha256);

    /** Assessments created in [from, to], newest first (daily brief). */
    List<ImpactAssessment> findByPortfolioIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
            UUID portfolioId, Instant from, Instant to);
}

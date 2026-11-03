package com.investary.journal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.journal.domain.DecisionEvidence;
import com.investary.journal.domain.EvidenceKind;

public interface DecisionEvidenceRepository extends JpaRepository<DecisionEvidence, UUID> {

    List<DecisionEvidence> findByDecisionIdOrderByAttachedAtAscIdAsc(UUID decisionId);

    Optional<DecisionEvidence> findByIdAndDecisionId(UUID id, UUID decisionId);

    boolean existsByDecisionIdAndKindAndSourceIdAndRemovedAtIsNull(UUID decisionId, EvidenceKind kind, UUID sourceId);
}

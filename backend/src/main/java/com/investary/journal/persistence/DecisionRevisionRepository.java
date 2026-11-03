package com.investary.journal.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.journal.domain.DecisionRevision;

public interface DecisionRevisionRepository extends JpaRepository<DecisionRevision, UUID> {

    List<DecisionRevision> findByDecisionIdOrderBySequenceAsc(UUID decisionId);

    long countByDecisionId(UUID decisionId);
}

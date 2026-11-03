package com.investary.journal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.journal.domain.DecisionTransactionLink;

public interface DecisionTransactionLinkRepository extends JpaRepository<DecisionTransactionLink, UUID> {

    List<DecisionTransactionLink> findByDecisionIdOrderByLinkedAtAscIdAsc(UUID decisionId);

    Optional<DecisionTransactionLink> findByIdAndDecisionId(UUID id, UUID decisionId);

    boolean existsByDecisionIdAndTransactionRefAndRemovedAtIsNull(UUID decisionId, UUID transactionRef);

    long countByDecisionIdAndRemovedAtIsNullAndTransactionIdIsNotNull(UUID decisionId);
}

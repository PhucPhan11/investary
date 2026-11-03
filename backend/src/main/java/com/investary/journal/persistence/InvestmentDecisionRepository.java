package com.investary.journal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.investary.journal.domain.InvestmentDecision;

public interface InvestmentDecisionRepository extends JpaRepository<InvestmentDecision, UUID>, JpaSpecificationExecutor<InvestmentDecision> {
}

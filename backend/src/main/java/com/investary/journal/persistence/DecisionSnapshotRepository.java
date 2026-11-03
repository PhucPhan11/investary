package com.investary.journal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.journal.domain.DecisionPortfolioSnapshot;

public interface DecisionSnapshotRepository extends JpaRepository<DecisionPortfolioSnapshot, UUID> {
}

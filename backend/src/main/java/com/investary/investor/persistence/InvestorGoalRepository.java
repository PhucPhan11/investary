package com.investary.investor.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.investor.domain.InvestorGoal;

public interface InvestorGoalRepository extends JpaRepository<InvestorGoal, UUID> {
}

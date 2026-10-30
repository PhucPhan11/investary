package com.investary.investor.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.investor.domain.InvestorProfile;

public interface InvestorProfileRepository extends JpaRepository<InvestorProfile, UUID> {

    Optional<InvestorProfile> findFirstByOrderByCreatedAtAsc();
}

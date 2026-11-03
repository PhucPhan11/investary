package com.investary.automation.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.investary.automation.domain.EmailSourceRule;

public interface EmailSourceRuleRepository extends JpaRepository<EmailSourceRule, UUID> {

    @Query("select r from EmailSourceRule r order by lower(r.name)")
    List<EmailSourceRule> findAllOrdered();

    @Query("select r from EmailSourceRule r where r.enabled = true order by r.createdAt, r.id")
    List<EmailSourceRule> findEnabled();

    @Query("select r from EmailSourceRule r where lower(r.name) = lower(:name)")
    Optional<EmailSourceRule> findByNameIgnoreCase(String name);
}

package com.investary.portfolio.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.investary.portfolio.domain.Portfolio;

import jakarta.persistence.LockModeType;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {

    List<Portfolio> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Serializes changes to one portfolio's transactions, so concurrent edits cannot oversell. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Portfolio p where p.id = :id")
    Optional<Portfolio> findForUpdate(UUID id);
}

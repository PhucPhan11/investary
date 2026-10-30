package com.investary.portfolio.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.portfolio.domain.PortfolioTransaction;

public interface PortfolioTransactionRepository extends JpaRepository<PortfolioTransaction, UUID> {

    /** Chronological order used for holding calculations. */
    List<PortfolioTransaction> findByPortfolioIdOrderByTradeDateAscCreatedAtAscIdAsc(UUID portfolioId);

    Page<PortfolioTransaction> findByPortfolioId(UUID portfolioId, Pageable pageable);

    Optional<PortfolioTransaction> findByIdAndPortfolioId(UUID id, UUID portfolioId);

    long countByPortfolioId(UUID portfolioId);

    boolean existsByInstrumentId(UUID instrumentId);
}

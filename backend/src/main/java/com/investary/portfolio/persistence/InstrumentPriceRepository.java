package com.investary.portfolio.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.investary.portfolio.domain.InstrumentPrice;

public interface InstrumentPriceRepository extends JpaRepository<InstrumentPrice, UUID> {

    List<InstrumentPrice> findByInstrumentIdOrderByAsOfDescCreatedAtDesc(UUID instrumentId);

    Optional<InstrumentPrice> findByIdAndInstrumentId(UUID id, UUID instrumentId);

    boolean existsByInstrumentIdAndAsOfAndSource(UUID instrumentId, LocalDate asOf, String source);

    /**
     * Price observations on or before {@code onOrBefore}, newest first per instrument; the caller keeps
     * the first row per instrument.
     */
    @Query("""
            select p from InstrumentPrice p
            where p.instrumentId in :instrumentIds and p.asOf <= :onOrBefore
            order by p.instrumentId, p.asOf desc, p.createdAt desc""")
    List<InstrumentPrice> findObservations(Collection<UUID> instrumentIds, LocalDate onOrBefore);
}

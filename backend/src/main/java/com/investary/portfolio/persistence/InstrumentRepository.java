package com.investary.portfolio.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.investary.portfolio.domain.Instrument;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {

    boolean existsBySymbol(String symbol);

    boolean existsBySymbolAndIdNot(String symbol, UUID id);

    List<Instrument> findAllByOrderBySymbolAsc();

    @Query("""
            select i from Instrument i
            where upper(i.symbol) like concat('%', upper(:text), '%') or upper(i.name) like concat('%', upper(:text), '%')
            order by i.symbol""")
    List<Instrument> search(String text);
}

package com.investary.research.extraction;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchItemRepository extends JpaRepository<ResearchItem, UUID> {

    List<ResearchItem> findBySourceMessageIdOrderBySourceLocation(UUID sourceMessageId);
}

package com.investary.research.link;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SourceLinkRepository extends JpaRepository<SourceLink, UUID> {

    List<SourceLink> findBySourceMessageIdOrderByPosition(UUID sourceMessageId);

    Optional<SourceLink> findByIdAndSourceMessageId(UUID id, UUID sourceMessageId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SourceLink l where l.id = :id and l.sourceMessageId = :messageId")
    Optional<SourceLink> lockByIdAndSourceMessageId(@Param("id") UUID id, @Param("messageId") UUID messageId);

    /** Links that pointed at a deleted document become downloadable again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SourceLink l set l.documentId = null, l.status = com.investary.research.link.LinkStatus.READY, l.errorMessage = null
            where l.documentId = :documentId""")
    int detachDocument(@Param("documentId") UUID documentId);
}

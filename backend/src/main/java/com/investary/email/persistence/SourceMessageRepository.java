package com.investary.email.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.investary.email.domain.SourceMessage;

public interface SourceMessageRepository extends JpaRepository<SourceMessage, UUID>, JpaSpecificationExecutor<SourceMessage> {

    Optional<SourceMessage> findByContentSha256(String contentSha256);

    @Query("select m.id from SourceMessage m where m.messageId = :messageId and m.id <> :excludedId order by m.importedAt")
    List<UUID> findIdsByMessageIdExcluding(String messageId, UUID excludedId);

    @Query("""
            select m.senderAddress as address, max(m.senderName) as name, count(m) as messages
            from SourceMessage m where m.senderAddress is not null
            group by m.senderAddress order by count(m) desc, m.senderAddress""")
    List<SenderSummary> findSenders();

    interface SenderSummary {
        String getAddress();

        String getName();

        long getMessages();
    }
}

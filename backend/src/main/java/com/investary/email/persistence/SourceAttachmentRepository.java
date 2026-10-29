package com.investary.email.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.email.domain.SourceAttachment;

public interface SourceAttachmentRepository extends JpaRepository<SourceAttachment, UUID> {

    List<SourceAttachment> findBySourceMessageIdOrderByPosition(UUID sourceMessageId);
}

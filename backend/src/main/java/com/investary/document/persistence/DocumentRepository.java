package com.investary.document.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.investary.document.domain.Document;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findBySha256Checksum(String sha256Checksum);
}

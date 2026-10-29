package com.investary.research.download;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadJobRepository extends JpaRepository<DownloadJob, UUID> {

    Optional<DownloadJob> findBySourceLinkId(UUID sourceLinkId);

    List<DownloadJob> findBySourceLinkIdIn(Collection<UUID> sourceLinkIds);
}

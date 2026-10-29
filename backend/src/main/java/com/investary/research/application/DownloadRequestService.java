package com.investary.research.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.research.download.DownloadException;
import com.investary.research.download.DownloadJob;
import com.investary.research.download.DownloadJobRepository;
import com.investary.research.download.DownloadProperties;
import com.investary.research.download.UrlPolicy;
import com.investary.research.link.LinkStatus;
import com.investary.research.link.SourceLink;
import com.investary.research.link.SourceLinkRepository;

/**
 * Accepts a user's request to download a linked report. Idempotent: a link that is already
 * downloading or downloaded is returned as is; a failed link is re-queued (this is the retry action).
 */
@Service
public class DownloadRequestService {

    private static final Logger log = LoggerFactory.getLogger(DownloadRequestService.class);

    private final SourceLinkRepository links;
    private final DownloadJobRepository jobs;
    private final UrlPolicy policy;
    private final DownloadProperties properties;
    private final Clock clock;

    public DownloadRequestService(SourceLinkRepository links, DownloadJobRepository jobs, UrlPolicy policy,
                                  DownloadProperties properties, Clock clock) {
        this.links = links;
        this.jobs = jobs;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @return {@code true} if a download is queued or in progress, {@code false} if the report is already stored
     * @throws DownloadNotAllowedException if the URL is not allowed by the download policy
     */
    @Transactional
    public boolean request(UUID messageId, UUID linkId) {
        // Row lock serializes concurrent requests for the same link.
        SourceLink link = links.lockByIdAndSourceMessageId(linkId, messageId).orElseThrow(() -> new LinkNotFoundException(linkId));
        if (link.getStatus() == LinkStatus.DOWNLOADED && link.getDocumentId() != null) {
            return false;
        }
        if (link.getStatus() == LinkStatus.DOWNLOADING) {
            return true;
        }
        try {
            policy.check(link.getTargetUrl());
        } catch (DownloadException e) {
            throw new DownloadNotAllowedException(e.getMessage());
        }
        Instant now = clock.instant();
        jobs.findBySourceLinkId(linkId).ifPresentOrElse(
                job -> job.requeue(properties.maxAttempts(), now),
                () -> jobs.save(new DownloadJob(linkId, properties.maxAttempts(), now)));
        link.markDownloading(now);
        log.info("Download requested linkId={} host={}", linkId, link.getHost());
        return true;
    }
}

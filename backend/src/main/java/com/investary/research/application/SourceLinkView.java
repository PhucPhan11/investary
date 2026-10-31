package com.investary.research.application;

import java.time.Instant;
import java.util.UUID;

import com.investary.research.download.DownloadJob;
import com.investary.research.download.DownloadJobStatus;
import com.investary.research.link.LinkCategory;
import com.investary.research.link.LinkStatus;
import com.investary.research.link.SourceLink;

/**
 * Public view of an extracted link and its download state.
 *
 * @param download current download job, or {@code null} if a download was never requested
 */
public record SourceLinkView(
        UUID id,
        int position,
        String displayText,
        String originalUrl,
        String targetUrl,
        String host,
        String section,
        String language,
        LinkCategory category,
        String classificationRule,
        String tickerHint,
        LinkStatus status,
        Instant discoveredAt,
        Instant lastAttemptAt,
        String errorMessage,
        UUID documentId,
        Download download) {

    static SourceLinkView of(SourceLink link, DownloadJob job) {
        return new SourceLinkView(link.getId(), link.getPosition(), link.getDisplayText(), link.getOriginalUrl(),
                link.getTargetUrl(), link.getHost(), link.getSection(), link.getLanguage(), link.getCategory(),
                link.getClassificationRule(), link.getTickerHint(), link.getStatus(), link.getDiscoveredAt(),
                link.getLastAttemptAt(), link.getErrorMessage(), link.getDocumentId(),
                job == null ? null : new Download(job.getStatus(), job.getAttempts(), job.getMaxAttempts(),
                        job.getNextAttemptAt(), job.getLastError(), job.getUpdatedAt()));
    }

    public record Download(DownloadJobStatus status, int attempts, int maxAttempts, Instant nextAttemptAt,
                           String lastError, Instant updatedAt) {
    }
}

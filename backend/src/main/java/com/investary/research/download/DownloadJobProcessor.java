package com.investary.research.download;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.document.application.DocumentService;
import com.investary.document.application.DuplicateDocumentException;
import com.investary.document.application.InvalidDocumentException;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.document.application.UploadOrigin;
import com.investary.document.domain.ReportType;
import com.investary.research.application.ReportDownloadedEvent;
import com.investary.research.download.DownloadJobQueue.ClaimedJob;
import com.investary.research.link.BrokerageRegistry;
import com.investary.research.link.LinkCategory;
import com.investary.research.link.SourceLink;
import com.investary.research.link.SourceLinkRepository;
import com.investary.research.provenance.ProvenanceRepository;
import com.investary.research.provenance.ProvenanceRepository.RelationshipType;

/**
 * Runs one claimed download job: fetch the PDF, store it through the Phase 1 document service
 * (reusing an identical existing document), and link it to the source message.
 */
@Component
public class DownloadJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(DownloadJobProcessor.class);

    private final ReportDownloader downloader;
    private final DocumentService documentService;
    private final SourceLinkRepository links;
    private final ProvenanceRepository provenance;
    private final DownloadJobQueue queue;
    private final BrokerageRegistry brokerages;
    private final DownloadProperties properties;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DownloadJobProcessor(ReportDownloader downloader, DocumentService documentService, SourceLinkRepository links,
                                ProvenanceRepository provenance, DownloadJobQueue queue, BrokerageRegistry brokerages,
                                DownloadProperties properties, TransactionTemplate transactions, ApplicationEventPublisher events,
                                Clock clock) {
        this.downloader = downloader;
        this.documentService = documentService;
        this.links = links;
        this.provenance = provenance;
        this.queue = queue;
        this.brokerages = brokerages;
        this.properties = properties;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
    }

    public void process(ClaimedJob job) {
        SourceLink link = links.findById(job.linkId()).orElse(null);
        if (link == null) {
            // The email was deleted; the job row went with it.
            return;
        }
        log.info("Download started jobId={} linkId={} attempt={}/{} host={}", job.jobId(), link.getId(), job.attempts(), job.maxAttempts(), link.getHost());
        try {
            UUID documentId = downloadAndStore(link);
            Boolean recorded = transactions.execute(status -> {
                if (!queue.complete(job)) {
                    log.warn("Download finished after its claim expired jobId={}; result kept in the library only", job.jobId());
                    return false;
                }
                links.findById(link.getId()).ifPresent(current -> current.markDownloaded(documentId, clock.instant()));
                provenance.record(link.getSourceMessageId(), documentId, RelationshipType.LINKED_REPORT, link.getId(), null);
                return true;
            });
            log.info("Download succeeded jobId={} linkId={} documentId={}", job.jobId(), link.getId(), documentId);
            if (Boolean.TRUE.equals(recorded)) {
                publishDownloaded(new ReportDownloadedEvent(link.getSourceMessageId(), documentId, link.getId()));
            }
        } catch (DownloadException e) {
            handleFailure(job, link.getId(), e.getMessage(), e.isRetryable());
        } catch (RuntimeException e) {
            log.error("Unexpected error in download jobId={}", job.jobId(), e);
            handleFailure(job, link.getId(), "Unexpected error while storing the report", true);
        }
    }

    private void publishDownloaded(ReportDownloadedEvent event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException e) {
            log.error("Report downloaded event failed linkId={}", event.linkId(), e);
        }
    }

    private UUID downloadAndStore(SourceLink link) {
        ReportDownloader.DownloadedFile file = downloader.download(link.getTargetUrl());
        try {
            var command = new UploadDocumentCommand(
                    new FileSystemResource(file.path()),
                    fileName(file.fileName(), link),
                    brokerages.identifyHost(link.getHost()).orElse(link.getSourceBrokerage()),
                    reportType(link.getCategory()),
                    link.getTickerHint(),
                    null,
                    UploadOrigin.LINKED_REPORT);
            try {
                return documentService.upload(command).getId();
            } catch (DuplicateDocumentException e) {
                return e.getExistingDocumentId();
            } catch (InvalidDocumentException e) {
                throw DownloadException.permanent(e.getMessage());
            }
        } finally {
            try {
                Files.deleteIfExists(file.path());
            } catch (IOException e) {
                log.warn("Could not delete temporary download path={}", file.path(), e);
            }
        }
    }

    private void handleFailure(ClaimedJob job, UUID linkId, String error, boolean retryable) {
        Instant now = clock.instant();
        boolean retry = retryable && job.attempts() < job.maxAttempts();
        transactions.executeWithoutResult(status -> {
            if (retry) {
                Duration backoff = properties.retryBackoff().multipliedBy(1L << Math.min(job.attempts() - 1, 10));
                if (queue.retryLater(job, error, now.plus(backoff))) {
                    links.findById(linkId).ifPresent(link -> link.recordAttemptError(error + " (will retry)", now));
                }
            } else if (queue.fail(job, error)) {
                links.findById(linkId).ifPresent(link -> link.markFailed(error, now));
            }
        });
        log.warn("Download failed jobId={} linkId={} attempt={}/{} retry={} reason={}", job.jobId(), linkId, job.attempts(), job.maxAttempts(), retry, error);
    }

    private static String fileName(String suggested, SourceLink link) {
        if (suggested != null && suggested.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return suggested;
        }
        String base = link.getDisplayText() != null ? link.getDisplayText() : "report";
        return base + ".pdf";
    }

    static ReportType reportType(LinkCategory category) {
        return switch (category) {
            case STOCK_RESEARCH -> ReportType.STOCK_ANALYSIS;
            case MARKET_REPORT -> ReportType.MARKET_OVERVIEW;
            default -> ReportType.OTHER;
        };
    }
}

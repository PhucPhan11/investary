package com.investary.automation.application.workflow;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.investary.automation.WorkflowProperties;
import com.investary.automation.application.workflow.StageResult.Done;
import com.investary.automation.application.workflow.StageResult.NextJob;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.domain.WorkflowTrigger;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore;
import com.investary.automation.persistence.WorkflowStore.GmailSource;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.research.application.DownloadNotAllowedException;
import com.investary.research.application.DownloadRequestService;
import com.investary.research.application.LinkNotFoundException;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.SourceLinkView;
import com.investary.research.link.LinkStatus;

import tools.jackson.databind.json.JsonMapper;

/**
 * Automatic PDF downloads, through the existing Phase 2 download pipeline only.
 * <p>
 * Policy: a link is requested automatically only when the email came from Gmail, its matched source rule
 * is enabled and has "automatic PDF download" on, and the classifier marked the link as a research PDF
 * ({@code READY}; navigation and promotion links are {@code SKIPPED} and never requested). Uploaded EML
 * files and rules without the option get {@code SKIPPED / AUTO_DOWNLOAD_DISABLED}; their links stay
 * available for the manual download button, and a manual download re-opens indexing and matching.
 * <p>
 * {@link DownloadRequestService} applies {@code UrlPolicy} (https, allowlisted domains, no credentials);
 * the download worker validates DNS/IP, redirects, size, time and PDF content, reuses identical documents
 * by checksum and records the email ↔ document provenance. The number of requests per email and per Gmail
 * sync run is capped.
 */
@Component
class DownloadStage implements StageHandler {

    private static final Logger log = LoggerFactory.getLogger(DownloadStage.class);
    private static final Duration GMAIL_MAPPING_WAIT = Duration.ofMinutes(5);

    private final ResearchQueryService research;
    private final DownloadRequestService downloads;
    private final WorkflowStore store;
    private final WorkflowProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    DownloadStage(ResearchQueryService research, DownloadRequestService downloads, WorkflowStore store, WorkflowProperties properties,
                  JsonMapper json, Clock clock) {
        this.research = research;
        this.downloads = downloads;
        this.store = store;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.DOWNLOADS;
    }

    @Override
    public StageResult run(ClaimedJob job, WorkflowRow workflow) {
        List<SourceLinkView> links = research.links(workflow.sourceId()).stream().filter(l -> l.category().isResearch()).toList();
        if (links.isEmpty()) {
            return done(StageStatus.SKIPPED, "NO_ELIGIBLE_LINKS", Map.of("researchLinks", 0));
        }
        if (job.resumed() && store.stage(workflow.id(), WorkflowStage.DOCUMENTS_PROCESSED).map(s -> s.counts().contains("\"requested\""))
                .orElse(false)) {
            return check(job, workflow, links);
        }
        GmailSource gmail = store.gmailSource(workflow.sourceId()).orElse(null);
        if (gmail == null && workflow.trigger() == WorkflowTrigger.GMAIL) {
            // Gmail sync records its message mapping (with the matched rule) right after the import returns.
            boolean expired = job.waitDeadline() != null && clock.instant().isAfter(job.waitDeadline());
            if (!expired) {
                return new StageResult.Wait(properties.waitPoll(), GMAIL_MAPPING_WAIT);
            }
        }
        return start(workflow, links, gmail);
    }

    private StageResult start(WorkflowRow workflow, List<SourceLinkView> links, GmailSource gmail) {
        List<SourceLinkView> candidates = links.stream().filter(l -> l.status() == LinkStatus.READY).toList();
        long downloaded = count(links, LinkStatus.DOWNLOADED);
        if (gmail == null || !gmail.autoDownload()) {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("researchLinks", links.size());
            counts.put("awaitingManualDownload", candidates.size());
            counts.put("downloaded", downloaded);
            return done(StageStatus.SKIPPED, "AUTO_DOWNLOAD_DISABLED", counts);
        }

        int budget = Math.max(0, properties.maxDownloadsPerEmail() - workflow.autoDownloadsRequested());
        if (gmail.syncRunId() != null) {
            store.linkSyncRun(workflow.id(), gmail.syncRunId());
            budget = Math.min(budget, Math.max(0, properties.maxDownloadsPerSyncRun() - store.autoDownloadsForSyncRun(gmail.syncRunId())));
        }
        int requested = 0;
        int blocked = 0;
        for (SourceLinkView link : candidates.stream().limit(budget).toList()) {
            try {
                downloads.request(workflow.sourceId(), link.id());
                requested++;
            } catch (DownloadNotAllowedException e) {
                blocked++;
            } catch (LinkNotFoundException e) {
                // The email was deleted meanwhile.
            }
        }
        int capped = Math.max(0, candidates.size() - budget);
        if (requested > 0) {
            store.addAutoDownloads(workflow.id(), requested);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("researchLinks", links.size());
        counts.put("requested", requested);
        counts.put("blocked", blocked);
        counts.put("overLimit", capped);
        store.updateStage(workflow.id(), WorkflowStage.DOCUMENTS_PROCESSED, StageStatus.RUNNING, null, json.writeValueAsString(counts));
        log.info("Workflow downloads requested workflowId={} requested={} blocked={} overLimit={}", workflow.id(), requested, blocked, capped);
        return new StageResult.Wait(properties.waitPoll(), properties.downloadWaitTimeout(), true);
    }

    private StageResult check(ClaimedJob job, WorkflowRow workflow, List<SourceLinkView> links) {
        Map<String, Object> counts = startCounts(workflow.id());
        long downloading = count(links, LinkStatus.DOWNLOADING);
        if (downloading > 0) {
            if (job.waitDeadline() != null && clock.instant().isAfter(job.waitDeadline())) {
                counts.put("stillDownloading", downloading);
                counts.put("downloaded", count(links, LinkStatus.DOWNLOADED));
                return done(StageStatus.PARTIAL, "DOWNLOAD_TIMEOUT", counts);
            }
            return new StageResult.Wait(properties.waitPoll(), properties.downloadWaitTimeout());
        }
        long downloaded = count(links, LinkStatus.DOWNLOADED);
        long failed = count(links, LinkStatus.FAILED);
        int blocked = intValue(counts.get("blocked"));
        int overLimit = intValue(counts.get("overLimit"));
        counts.put("downloaded", downloaded);
        counts.put("failed", failed);
        if (failed == 0 && blocked == 0 && overLimit == 0) {
            return done(downloaded > 0 ? StageStatus.COMPLETED : StageStatus.SKIPPED, downloaded > 0 ? null : "NO_ELIGIBLE_LINKS", counts);
        }
        if (downloaded == 0 && overLimit == 0) {
            return done(StageStatus.FAILED, "DOWNLOADS_FAILED", counts);
        }
        return done(StageStatus.PARTIAL, failed + blocked > 0 ? "SOME_DOWNLOADS_FAILED" : "DOWNLOAD_LIMIT_REACHED", counts);
    }

    private Map<String, Object> startCounts(UUID workflowId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        store.stage(workflowId, WorkflowStage.DOCUMENTS_PROCESSED).map(WorkflowStore.StageRow::counts).ifPresent(text -> {
            json.readTree(text).properties().forEach(entry -> counts.put(entry.getKey(),
                    entry.getValue().isNumber() ? entry.getValue().asLong() : entry.getValue().asString()));
        });
        return counts;
    }

    private static Done done(StageStatus status, String reason, Map<String, Object> counts) {
        return Done.of(status, reason, counts, List.of(NextJob.of(JobType.INDEX)));
    }

    private static long count(List<SourceLinkView> links, LinkStatus status) {
        return links.stream().filter(l -> l.status() == status).count();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

package com.investary.knowledge.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.index.KnowledgeIndexService;
import com.investary.knowledge.index.KnowledgeJob;
import com.investary.knowledge.index.KnowledgeStatus;
import com.investary.knowledge.index.ReindexScope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Knowledge base indexing. Indexing always runs in the background; these endpoints only queue work
 * and report state.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeIndexService service;

    public KnowledgeController(KnowledgeIndexService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public KnowledgeStatus status() {
        return service.status();
    }

    @PostMapping("/index/documents/{id}")
    public ResponseEntity<JobResponse> indexDocument(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(view(service.request(KnowledgeSourceType.DOCUMENT, id)));
    }

    @PostMapping("/index/emails/{id}")
    public ResponseEntity<JobResponse> indexEmail(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(view(service.request(KnowledgeSourceType.EMAIL, id)));
    }

    @GetMapping("/documents/{id}")
    public JobResponse documentStatus(@PathVariable UUID id) {
        return service.forSource(KnowledgeSourceType.DOCUMENT, id).map(this::view)
                .orElseGet(() -> JobResponse.notIndexed(KnowledgeSourceType.DOCUMENT, id));
    }

    @GetMapping("/emails/{id}")
    public JobResponse emailStatus(@PathVariable UUID id) {
        return service.forSource(KnowledgeSourceType.EMAIL, id).map(this::view)
                .orElseGet(() -> JobResponse.notIndexed(KnowledgeSourceType.EMAIL, id));
    }

    @GetMapping("/jobs/{id}")
    public JobResponse job(@PathVariable UUID id) {
        return view(service.job(id));
    }

    @PostMapping("/reindex")
    public ResponseEntity<ReindexResponse> reindex(@Valid @RequestBody ReindexRequest request) {
        int queued = service.reindex(request.scope());
        return ResponseEntity.accepted().body(new ReindexResponse(request.scope(), queued));
    }

    private JobResponse view(KnowledgeJob job) {
        return new JobResponse(job.id(), job.sourceType(), job.sourceId(), job.status().name(), job.statusReason(),
                job.indexVersion(), job.indexVersion() != null && service.isCurrent(job), job.chunkCount(), job.pageCount(),
                job.attempts(), job.maxAttempts(), job.lastError(), job.requestedAt(), job.startedAt(), job.completedAt(),
                job.nextAttemptAt());
    }

    public record ReindexRequest(@NotNull ReindexScope scope) {
    }

    public record ReindexResponse(ReindexScope scope, int queued) {
    }

    /**
     * @param status        PENDING, PROCESSING, INDEXED, FAILED, SKIPPED, or NOT_INDEXED when the source is not tracked yet
     * @param currentVersion whether the stored chunks were built with the active configuration (only those are searched)
     */
    public record JobResponse(UUID id, KnowledgeSourceType sourceType, UUID sourceId, String status, String statusReason,
                              String indexVersion, boolean currentVersion, int chunkCount, Integer pageCount, int attempts,
                              int maxAttempts, String lastError, Instant requestedAt, Instant startedAt, Instant completedAt,
                              Instant nextAttemptAt) {

        static JobResponse notIndexed(KnowledgeSourceType type, UUID sourceId) {
            return new JobResponse(null, type, sourceId, "NOT_INDEXED", null, null, false, 0, null, 0, 0, null, null,
                    null, null, null);
        }
    }
}

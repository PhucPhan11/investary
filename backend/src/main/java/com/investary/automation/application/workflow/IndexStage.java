package com.investary.automation.application.workflow;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.investary.automation.WorkflowProperties;
import com.investary.automation.application.workflow.StageResult.Done;
import com.investary.automation.application.workflow.StageResult.NextJob;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.common.error.ApiException;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.embedding.EmbeddingService;
import com.investary.knowledge.index.KnowledgeIndexService;
import com.investary.knowledge.index.KnowledgeJob;
import com.investary.knowledge.index.KnowledgeSourceStatus;

/**
 * Indexes the email and its documents (or the library document) through the existing Phase 3 queue and
 * waits for the knowledge worker. A source is only re-queued when it has no index entry, failed, or was
 * indexed with another pipeline/model version; unchanged content is not re-embedded (the processor
 * compares content hashes and reuses chunk vectors). The email (or library document) must be indexed;
 * a report that cannot be indexed only makes the stage partial.
 */
@Component
class IndexStage implements StageHandler {

    private final KnowledgeIndexService index;
    private final EmbeddingService embeddings;
    private final WorkflowSources sources;
    private final WorkflowProperties properties;
    private final Clock clock;

    IndexStage(KnowledgeIndexService index, EmbeddingService embeddings, WorkflowSources sources, WorkflowProperties properties,
               Clock clock) {
        this.index = index;
        this.embeddings = embeddings;
        this.sources = sources;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.INDEX;
    }

    @Override
    public StageResult run(ClaimedJob job, WorkflowRow workflow) {
        if (!embeddings.isAvailable()) {
            return Done.of(StageStatus.SKIPPED, "EMBEDDINGS_NOT_CONFIGURED", Map.of(), List.of(NextJob.of(JobType.MATCH)));
        }
        List<Target> targets = targets(workflow);
        if (!job.resumed()) {
            targets.forEach(this::requestIfNeeded);
        }
        int pending = 0;
        int indexed = 0;
        int skipped = 0;
        int chunks = 0;
        List<String> optionalFailures = new ArrayList<>();
        String mandatoryFailure = null;
        for (Target target : targets) {
            Optional<KnowledgeJob> state = state(target);
            if (state.isEmpty()) {
                if (target.mandatory()) {
                    return new StageResult.Failed("SOURCE_DELETED", "The source no longer exists", false);
                }
                continue;
            }
            KnowledgeJob knowledge = state.get();
            switch (knowledge.status()) {
                case PENDING, PROCESSING -> pending++;
                case INDEXED -> {
                    indexed++;
                    chunks += knowledge.chunkCount();
                }
                case SKIPPED -> skipped++;
                case FAILED -> {
                    String error = FailureClassifier.safe(knowledge.lastError());
                    if (target.mandatory()) {
                        mandatoryFailure = error == null ? "Indexing failed" : error;
                    } else {
                        optionalFailures.add(target.id().toString());
                    }
                }
            }
        }
        if (mandatoryFailure != null) {
            return new StageResult.Failed("INDEXING_FAILED", mandatoryFailure, false);
        }
        if (pending > 0) {
            if (job.waitDeadline() != null && clock.instant().isAfter(job.waitDeadline())) {
                return new StageResult.Failed("INDEX_TIMEOUT", "Indexing did not finish within " + properties.indexWaitTimeout(), false);
            }
            return new StageResult.Wait(properties.waitPoll(), properties.indexWaitTimeout());
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("sources", targets.size());
        counts.put("indexed", indexed);
        counts.put("skipped", skipped);
        counts.put("failed", optionalFailures.size());
        counts.put("chunks", chunks);
        StageStatus status = optionalFailures.isEmpty() ? StageStatus.COMPLETED : StageStatus.PARTIAL;
        return Done.of(status, optionalFailures.isEmpty() ? null : "SOME_DOCUMENTS_NOT_INDEXED", counts, List.of(NextJob.of(JobType.MATCH)));
    }

    private List<Target> targets(WorkflowRow workflow) {
        List<Target> targets = new ArrayList<>();
        if (workflow.sourceType() == WorkflowSourceType.DOCUMENT) {
            targets.add(new Target(KnowledgeSourceType.DOCUMENT, workflow.sourceId(), true));
            return targets;
        }
        targets.add(new Target(KnowledgeSourceType.EMAIL, workflow.sourceId(), true));
        sources.linkedDocuments(workflow.sourceId())
                .forEach(document -> targets.add(new Target(KnowledgeSourceType.DOCUMENT, document.documentId(), false)));
        return targets;
    }

    private void requestIfNeeded(Target target) {
        Optional<KnowledgeJob> state = state(target);
        if (state.isEmpty()) {
            return;
        }
        KnowledgeJob job = state.get();
        boolean active = job.status() == KnowledgeSourceStatus.PENDING || job.status() == KnowledgeSourceStatus.PROCESSING;
        boolean done = (job.status() == KnowledgeSourceStatus.INDEXED || job.status() == KnowledgeSourceStatus.SKIPPED) && index.isCurrent(job);
        if (!active && !done) {
            index.request(target.type(), target.id());
        }
    }

    /**
     * @return the index entry, a placeholder request for an untracked source, or empty if the source is gone
     */
    private Optional<KnowledgeJob> state(Target target) {
        try {
            Optional<KnowledgeJob> job = index.forSource(target.type(), target.id());
            return job.isPresent() ? job : Optional.of(index.request(target.type(), target.id()));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    private record Target(KnowledgeSourceType type, UUID id, boolean mandatory) {
    }
}

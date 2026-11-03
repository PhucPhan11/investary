package com.investary.automation.application.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.investary.automation.application.workflow.StageResult.Done;
import com.investary.automation.application.workflow.StageResult.NextJob;
import com.investary.automation.application.workflow.StageResult.StageUpdate;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.SourceLinkView;
import com.investary.research.link.LinkStatus;

/**
 * Records the import stages. Parsing, link discovery/classification and research items already happened
 * synchronously in the Phase 2 import, so this stage only reads their results; it never parses again.
 */
@Component
class PrepareStage implements StageHandler {

    private final WorkflowSources sources;
    private final ResearchQueryService research;

    PrepareStage(WorkflowSources sources, ResearchQueryService research) {
        this.sources = sources;
        this.research = research;
    }

    @Override
    public JobType type() {
        return JobType.PREPARE;
    }

    @Override
    public StageResult run(ClaimedJob job, WorkflowRow workflow) {
        if (workflow.sourceType() == WorkflowSourceType.DOCUMENT) {
            sources.document(workflow.sourceId());
            List<StageUpdate> notApplicable = List.of(
                    new StageUpdate(WorkflowStage.EMAIL_IMPORTED, StageStatus.SKIPPED, "NOT_APPLICABLE", Map.of()),
                    new StageUpdate(WorkflowStage.EMAIL_PARSED, StageStatus.SKIPPED, "NOT_APPLICABLE", Map.of()),
                    new StageUpdate(WorkflowStage.DOCUMENTS_PROCESSED, StageStatus.SKIPPED, "NOT_APPLICABLE", Map.of()));
            return Done.of(StageStatus.SKIPPED, "NOT_APPLICABLE", Map.of(), List.of(NextJob.of(JobType.INDEX)))
                    .withStages(notApplicable);
        }

        EmailDetail email = sources.email(workflow.sourceId());
        List<SourceLinkView> links = research.links(workflow.sourceId());
        int researchItems = research.researchItems(workflow.sourceId()).size();

        StageStatus parsed = switch (email.parsingStatus()) {
            case PARSED -> StageStatus.COMPLETED;
            case PARTIAL -> StageStatus.PARTIAL;
            case FAILED -> StageStatus.FAILED;
        };
        Map<String, Object> linkCounts = new LinkedHashMap<>();
        linkCounts.put("links", links.size());
        linkCounts.put("researchLinks", links.stream().filter(l -> l.category().isResearch()).count());
        linkCounts.put("pdfLinks", links.stream().filter(l -> l.category().isResearch() && l.status() == LinkStatus.READY).count());
        linkCounts.put("ignoredLinks", links.stream().filter(l -> l.status() == LinkStatus.SKIPPED).count());
        linkCounts.put("researchItems", researchItems);

        List<StageUpdate> stages = List.of(
                new StageUpdate(WorkflowStage.EMAIL_IMPORTED, StageStatus.COMPLETED, null, Map.of("attachments", email.attachments().size())),
                new StageUpdate(WorkflowStage.EMAIL_PARSED, parsed, parsed == StageStatus.COMPLETED ? null : "PARSING_" + email.parsingStatus(),
                        Map.of()));
        StageStatus linkStatus = links.isEmpty() ? StageStatus.SKIPPED : StageStatus.COMPLETED;
        return Done.of(linkStatus, links.isEmpty() ? "NO_LINKS" : null, linkCounts, List.of(NextJob.of(JobType.DOWNLOADS)))
                .withStages(stages);
    }
}

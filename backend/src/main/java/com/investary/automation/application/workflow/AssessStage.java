package com.investary.automation.application.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.investary.automation.application.settings.AutomationSettings;
import com.investary.automation.application.workflow.StageResult.Done;
import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.intelligence.application.ImpactAssessmentService;
import com.investary.intelligence.application.ImpactAssessmentService.AssessmentTrigger;
import com.investary.intelligence.application.ImpactAssessmentService.SourceAssessment;
import com.investary.knowledge.usage.AiSettings;

/**
 * One impact assessment for one matched portfolio, through the Phase 4 service (deterministic tiers,
 * evidence from stored chunks, {@code CitationValidator}; the model can only lower confidence). It runs
 * only for portfolios the deterministic match found, at most {@code max-auto-assessments-per-day} new
 * assessments per Asia/Ho_Chi_Minh day, and never twice for the same portfolio, holdings and source content.
 * Model calls share the process-wide chat concurrency limit.
 */
@Component
class AssessStage implements StageHandler {

    private final ImpactAssessmentService assessments;
    private final AutomationJobQueue queue;
    private final WorkflowSources sources;
    private final AutomationSettings settings;
    private final AiSettings aiSettings;
    private final Clock clock;

    AssessStage(ImpactAssessmentService assessments, AutomationJobQueue queue, WorkflowSources sources, AutomationSettings settings,
                AiSettings aiSettings, Clock clock) {
        this.assessments = assessments;
        this.queue = queue;
        this.sources = sources;
        this.settings = settings;
        this.aiSettings = aiSettings;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.ASSESS;
    }

    @Override
    public StageResult run(ClaimedJob job, WorkflowRow workflow) {
        if (!settings.workflow().autoAssess()) {
            return Done.of(StageStatus.SKIPPED, "AUTO_ASSESS_DISABLED", Map.of(), List.of());
        }
        Instant startOfDay = clock.instant().atZone(MatchStage.MARKET_ZONE).toLocalDate().atStartOfDay(MatchStage.MARKET_ZONE).toInstant();
        if (queue.countCreatedAssessmentsSince(startOfDay) >= settings.workflow().maxAutoAssessmentsPerDay()) {
            return Done.of(StageStatus.SKIPPED, "DAILY_LIMIT_REACHED", Map.of(), List.of());
        }
        if (aiSettings.automaticTokenBudgetReached()) {
            return Done.of(StageStatus.SKIPPED, "TOKEN_BUDGET_REACHED", Map.of(), List.of());
        }
        SourceAssessment result = assessments.createForSource(job.portfolioId(),
                new AssessmentTrigger(workflow.sourceType().name(), workflow.sourceId(), sources.fingerprint(workflow), job.id()));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("assessmentId", result.assessmentId().toString());
        counts.put("aiStatus", result.aiStatus().name());
        return Done.of(StageStatus.COMPLETED, result.created() ? "CREATED" : "ALREADY_ASSESSED", counts, List.of())
                .withTokens(result.inputTokens(), result.outputTokens());
    }
}

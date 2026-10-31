package com.investary.automation.application.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investary.automation.domain.JobType;
import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowStage;

/**
 * What a stage handler decided. Handlers never write job state themselves; {@link WorkflowJobProcessor}
 * applies the result in one short transaction, guarded by the claim token.
 */
public sealed interface StageResult {

    /**
     * The stage is finished.
     *
     * @param status       status of the job's stage
     * @param reason       short machine-readable reason (e.g. {@code NO_ELIGIBLE_LINKS}); also the job outcome
     * @param counts       counters shown with the stage
     * @param otherStages  status of other stages decided by this job (e.g. PREPARE sets the import stages)
     * @param next         jobs to enqueue next
     * @param inputTokens  provider-reported prompt tokens, or null
     * @param outputTokens provider-reported completion tokens, or null
     */
    record Done(StageStatus status, String reason, Map<String, Object> counts, List<StageUpdate> otherStages, List<NextJob> next,
                Integer inputTokens, Integer outputTokens) implements StageResult {

        public static Done of(StageStatus status, String reason, Map<String, Object> counts, List<NextJob> next) {
            return new Done(status, reason, counts, List.of(), next, null, null);
        }

        public Done withStages(List<StageUpdate> stages) {
            return new Done(status, reason, counts, stages, next, inputTokens, outputTokens);
        }

        public Done withTokens(Integer input, Integer output) {
            return new Done(status, reason, counts, otherStages, next, input, output);
        }
    }

    /**
     * Work another worker does is still running; check again after {@code delay}, giving up at the
     * deadline that the first wait set ({@code timeout} from then).
     *
     * @param restartDeadline start a new deadline now (the job moved on to waiting for something else)
     */
    record Wait(Duration delay, Duration timeout, boolean restartDeadline) implements StageResult {

        public Wait(Duration delay, Duration timeout) {
            this(delay, timeout, false);
        }
    }

    /**
     * @param code      short error code shown to the user
     * @param summary   safe summary (no stack trace, credentials or URLs)
     * @param retryable transient failure (network, rate limit, provider outage)
     */
    record Failed(String code, String summary, boolean retryable) implements StageResult {
    }

    record StageUpdate(WorkflowStage stage, StageStatus status, String reason, Map<String, Object> counts) {
    }

    /**
     * @param keyPart extra idempotency key part (ASSESS: portfolio, holdings and content fingerprints)
     */
    record NextJob(JobType type, UUID portfolioId, String keyPart) {

        public static NextJob of(JobType type) {
            return new NextJob(type, null, null);
        }
    }
}

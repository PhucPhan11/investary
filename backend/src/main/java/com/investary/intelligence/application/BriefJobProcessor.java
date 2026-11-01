package com.investary.intelligence.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorSummaries;
import com.investary.common.tx.AfterCommit;
import com.investary.intelligence.application.BriefGenerator.Draft;
import com.investary.intelligence.application.BriefGenerator.RetryLater;
import com.investary.intelligence.application.BriefViews.BriefContent;
import com.investary.intelligence.domain.BriefAiStatus;
import com.investary.intelligence.domain.BriefJobOutcome;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.persistence.BriefJobQueue;
import com.investary.intelligence.persistence.BriefJobQueue.ClaimedJob;
import com.investary.intelligence.persistence.BriefStore;
import com.investary.intelligence.persistence.BriefStore.BriefRow;
import com.investary.intelligence.persistence.BriefStore.NewBrief;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one claimed brief job: generation happens with no transaction open; the result (a new revision, or
 * "unchanged") is written in one short transaction that first re-checks the claim token, so a worker whose
 * lease expired cannot write a second brief. Transient failures are retried with backoff; after the last
 * attempt a FAILED brief row records the safe error summary.
 */
@Service
public class BriefJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(BriefJobProcessor.class);

    private final BriefGenerator generator;
    private final BriefJobQueue queue;
    private final BriefStore store;
    private final BriefSettings settings;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BriefJobProcessor(BriefGenerator generator, BriefJobQueue queue, BriefStore store, BriefSettings settings,
                      TransactionTemplate transactions, JsonMapper json, ApplicationEventPublisher events, Clock clock) {
        this.events = events;
        this.generator = generator;
        this.queue = queue;
        this.store = store;
        this.settings = settings;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    public void process(ClaimedJob job) {
        try {
            Draft draft = generator.generate(job);
            apply(job, draft);
        } catch (RetryLater e) {
            retry(job, e.code(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Lost a race for the same revision or scheduled period: the next attempt appends a revision or finds it unchanged.
            retryOrFail(job, "CONCURRENT_GENERATION", "Another generation for the same period finished first", true);
        } catch (RuntimeException e) {
            Failure failure = classify(e);
            log.warn("Brief job {} failed (attempt {}/{}): {}", job.id(), job.attempts(), job.maxAttempts(), failure.code());
            retryOrFail(job, failure.code(), failure.summary(), failure.retryable());
        }
    }

    private void apply(ClaimedJob job, Draft draft) {
        transactions.executeWithoutResult(status -> {
            if (!queue.lockClaimed(job)) {
                log.info("Brief job {} is no longer claimed by this worker; result dropped", job.id());
                return;
            }
            if (draft.unchanged()) {
                queue.complete(job, BriefJobOutcome.UNCHANGED, draft.unchangedBriefId(), null, null);
                return;
            }
            NewBrief brief = insert(job, draft.status(), draft, null, null);
            if (queue.complete(job, draft.status() == BriefStatus.SKIPPED ? BriefJobOutcome.SKIPPED : BriefJobOutcome.CREATED, brief.id(),
                    draft.inputTokens(), draft.outputTokens())) {
                BriefGeneratedEvent event = new BriefGeneratedEvent(brief.id(), brief.rootBriefId(), brief.revision(), job.portfolioId(),
                        job.trigger(), draft.status(), draft.researchState(), job.periodEnd(), job.businessDate(), draft.conflicts());
                AfterCommit.run(() -> events.publishEvent(event));
            }
        });
    }

    private void retryOrFail(ClaimedJob job, String code, String summary, boolean retryable) {
        if (retryable && job.attempts() < job.maxAttempts()) {
            retry(job, code, summary);
            return;
        }
        boolean portfolioGone = "SOURCE_DELETED".equals(code);
        transactions.executeWithoutResult(status -> {
            if (!queue.lockClaimed(job)) {
                return;
            }
            UUID briefId = portfolioGone ? null : insert(job, BriefStatus.FAILED, null, code, summary).id();
            if (queue.fail(job, code, summary, briefId)) {
                BriefJobFailedEvent event = new BriefJobFailedEvent(job.id(), job.portfolioId(), job.businessDate(), code);
                AfterCommit.run(() -> events.publishEvent(event));
            }
        });
    }

    private void retry(ClaimedJob job, String code, String summary) {
        Instant next = clock.instant().plus(ErrorSummaries.backoff(settings.current().retryBackoff(), job.attempts(),
                ThreadLocalRandom.current().nextDouble()));
        queue.retryLater(job, code, summary, next);
    }

    /** Appends a revision to the chain of this scope and period (or of the regenerated brief). */
    private NewBrief insert(ClaimedJob job, BriefStatus status, Draft draft, String errorCode, String errorSummary) {
        Optional<BriefRow> head = job.trigger() == BriefTrigger.REGENERATION ? store.headOfChain(job.previousBriefId())
                : store.head(job.portfolioId(), job.periodStart(), job.periodEnd());
        UUID id = UUID.randomUUID();
        int revision = head.map(h -> h.revision() + 1).orElse(1);
        UUID root = head.map(BriefRow::rootBriefId).orElse(id);
        UUID previous = head.map(BriefRow::id).orElse(null);
        Instant now = clock.instant();
        NewBrief brief = draft == null
                ? new NewBrief(id, job.portfolioId(), job.trigger(), status, null, job.periodStart(), job.periodEnd(), job.zone(),
                        job.businessDate(), job.partialPeriod(), now, now, json.writeValueAsString(new BriefContent(null, 0, 0, List.of())),
                        "[]", BriefAiStatus.FAILED, null, BriefPrompts.VERSION, null, job.configVersion(), null, null, null, null,
                        errorCode, errorSummary, null, null, revision, root, previous, job.id())
                : new NewBrief(id, job.portfolioId(), job.trigger(), status, draft.researchState(), job.periodStart(), job.periodEnd(),
                        job.zone(), job.businessDate(), job.partialPeriod(), now, draft.sourceCutoff(), draft.contentJson(),
                        draft.limitationsJson(), draft.aiStatus(), draft.model(), BriefPrompts.VERSION, draft.indexVersion(),
                        job.configVersion(), draft.inputFingerprint(), draft.portfolioSnapshotJson(), draft.snapshotHash(),
                        draft.evidenceStatus(), draft.errorCode(), draft.errorSummary(), draft.inputTokens(), draft.outputTokens(), revision,
                        root, previous, job.id());
        store.insert(brief, draft == null ? List.of() : draft.sources());
        return brief;
    }

    private static Failure classify(RuntimeException e) {
        if (e instanceof ApiException api) {
            if (api.getStatus() == HttpStatus.NOT_FOUND) {
                return new Failure("SOURCE_DELETED", "The portfolio or brief no longer exists", false);
            }
            boolean retryable = api.getStatus().is5xxServerError() || api.getStatus() == HttpStatus.TOO_MANY_REQUESTS;
            return new Failure(api.getCode().name(), ErrorSummaries.safe(api.getMessage()), retryable);
        }
        if (e instanceof TransientDataAccessException || e instanceof DataAccessResourceFailureException) {
            return new Failure("DATABASE_UNAVAILABLE", "The database was temporarily unavailable", true);
        }
        return new Failure("INTERNAL_ERROR", "Unexpected error (" + e.getClass().getSimpleName() + ")", true);
    }

    private record Failure(String code, String summary, boolean retryable) {
    }
}

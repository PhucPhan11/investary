package com.investary.intelligence.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.investary.common.web.PageResponse;
import com.investary.intelligence.application.BriefExceptions.BriefJobNotFoundException;
import com.investary.intelligence.application.BriefExceptions.BriefNotFoundException;
import com.investary.intelligence.application.BriefExceptions.BriefsDisabledException;
import com.investary.intelligence.application.BriefPeriods.Period;
import com.investary.intelligence.application.BriefViews.BriefCitation;
import com.investary.intelligence.application.BriefViews.BriefContent;
import com.investary.intelligence.application.BriefViews.BriefJobView;
import com.investary.intelligence.application.BriefViews.BriefListItem;
import com.investary.intelligence.application.BriefViews.BriefSourceRef;
import com.investary.intelligence.application.BriefViews.BriefView;
import com.investary.intelligence.application.BriefViews.Limitation;
import com.investary.intelligence.application.BriefViews.RevisionRef;
import com.investary.intelligence.domain.BriefSourceRole;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.persistence.BriefJobQueue;
import com.investary.intelligence.persistence.BriefJobQueue.JobRequest;
import com.investary.intelligence.persistence.BriefJobQueue.JobRow;
import com.investary.intelligence.persistence.BriefStore;
import com.investary.intelligence.persistence.BriefStore.BriefRow;
import com.investary.intelligence.persistence.BriefStore.ListFilter;
import com.investary.intelligence.persistence.BriefStore.SourceRow;
import com.investary.portfolio.application.PortfolioQueryService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Daily brief API: reads briefs and requests generation. Generation itself always runs on the brief job
 * queue ({@link BriefJobProcessor}); requests only enqueue, so they return at once (202).
 */
@Service
public class BriefService {

    private final BriefStore store;
    private final BriefJobQueue queue;
    private final BriefProperties properties;
    private final PortfolioQueryService portfolios;
    private final BriefPeriods periods;
    private final JsonMapper json;
    private final Clock clock;

    BriefService(BriefStore store, BriefJobQueue queue, BriefProperties properties, PortfolioQueryService portfolios, JsonMapper json,
                 Clock clock) {
        this.store = store;
        this.queue = queue;
        this.properties = properties;
        this.portfolios = portfolios;
        this.periods = new BriefPeriods(properties.schedule().cron(), properties.schedule().zoneId());
        this.json = json;
        this.clock = clock;
    }

    public PageResponse<BriefListItem> list(UUID portfolioId, LocalDate from, LocalDate to, BriefStatus status, int page, int size) {
        ListFilter filter = new ListFilter(portfolioId, from, to, status);
        long total = store.count(filter);
        List<BriefListItem> items = store.list(filter, page, size).stream().map(this::listItem).toList();
        return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
    }

    public BriefView view(UUID id) {
        BriefRow row = store.find(id).orElseThrow(() -> new BriefNotFoundException(id));
        BriefContent content = content(row);
        List<SourceRow> sources = store.sources(id);
        List<BriefCitation> citations = sources.stream().filter(s -> s.label() != null)
                .map(s -> new BriefCitation(s.label(), s.role(), s.sourceType(), s.sourceId(), s.title(), s.brokerage(), s.publishedOn(),
                        s.dateBasis(), s.sentAt(), s.importedAt(), s.indexedAt(), s.pageStart(), s.pageEnd(), s.section(), s.location(),
                        s.excerpt(), s.symbols(), s.relevance(), s.oldPublication(), s.lateArrival(), s.receivedVia()))
                .toList();
        List<RevisionRef> revisions = store.revisions(row.rootBriefId()).stream()
                .map(r -> new RevisionRef(r.id(), r.revision(), r.trigger(), r.status(), r.generatedAt())).toList();
        return new BriefView(row.id(), row.portfolioId(), content.portfolioName(), row.briefType(), row.trigger(), row.status(),
                row.researchState(), row.periodStart(), row.periodEnd(), row.zone(), row.businessDate(), row.partialPeriod(),
                row.generatedAt(), row.sourceCutoff(), row.language(), content.sections(), citations, refs(sources, BriefSourceRole.PENDING),
                refs(sources, BriefSourceRole.OMITTED), json.readValue(row.limitationsJson(), new TypeReference<List<Limitation>>() { }),
                row.aiStatus(), row.model(), row.promptVersion(), row.configVersion(), row.evidenceStatus(), row.errorCode(),
                row.errorSummary(), row.inputTokens(), row.outputTokens(), row.revision(), row.rootBriefId(), row.previousRevisionId(),
                revisions, BriefViews.DISCLAIMER);
    }

    /**
     * Requests a brief for {@code portfolioId} (null: a general brief) and the period ending on {@code date}
     * (default: today in the brief zone). A request for the same scope and period while one is running
     * returns that job.
     */
    public BriefJobView generate(UUID portfolioId, LocalDate date) {
        requireEnabled();
        if (portfolioId != null) {
            portfolios.portfolio(portfolioId);
        }
        Instant now = clock.instant();
        LocalDate day = date == null ? now.atZone(periods.zone()).toLocalDate() : date;
        Period period = periods.forDate(day, now);
        JobRequest request = new JobRequest(BriefTrigger.MANUAL, portfolioId, period.start(), period.end(), period.zone(),
                period.businessDate(), period.partial(), null, properties.configVersion());
        String key = "manual:" + (portfolioId == null ? "general" : portfolioId) + ":" + period.start() + ":" + day;
        return job(queue.enqueue(request, key, properties.maxAttempts(), true).id());
    }

    /** Re-runs the brief's scope and period; the result is a new revision unless the inputs are unchanged. */
    public BriefJobView regenerate(UUID id) {
        requireEnabled();
        BriefRow row = store.find(id).orElseThrow(() -> new BriefNotFoundException(id));
        JobRequest request = new JobRequest(BriefTrigger.REGENERATION, row.portfolioId(), row.periodStart(), row.periodEnd(), row.zone(),
                row.businessDate(), row.partialPeriod(), row.id(), properties.configVersion());
        return job(queue.enqueue(request, "regenerate:" + row.rootBriefId(), properties.maxAttempts(), true).id());
    }

    public BriefJobView job(UUID id) {
        JobRow job = queue.find(id).orElseThrow(() -> new BriefJobNotFoundException(id));
        return new BriefJobView(job.id(), job.trigger(), job.status(), job.outcome(), job.portfolioId(), job.periodStart(), job.periodEnd(),
                job.zone(), job.businessDate(), job.partialPeriod(), job.previousBriefId(), job.attempts(), job.maxAttempts(),
                job.nextAttemptAt(), job.briefId(), job.errorCode(), job.errorSummary(), job.createdAt(), job.startedAt(), job.completedAt());
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BriefsDisabledException();
        }
    }

    private BriefListItem listItem(BriefRow row) {
        BriefContent content = content(row);
        return new BriefListItem(row.id(), row.portfolioId(), content.portfolioName(), row.trigger(), row.status(), row.researchState(),
                row.periodStart(), row.periodEnd(), row.zone(), row.businessDate(), row.partialPeriod(), row.generatedAt(), row.revision(),
                row.aiStatus(), row.evidenceStatus(), content.newSources(), content.pendingSources(), row.errorCode());
    }

    private BriefContent content(BriefRow row) {
        BriefContent content = json.readValue(row.contentJson(), BriefContent.class);
        return content.sections() == null
                ? new BriefContent(content.portfolioName(), content.newSources(), content.pendingSources(), List.of())
                : content;
    }

    private static List<BriefSourceRef> refs(List<SourceRow> sources, BriefSourceRole role) {
        return sources.stream().filter(s -> s.role() == role)
                .map(s -> new BriefSourceRef(role, s.sourceType(), s.sourceId(), s.title(), s.brokerage(), s.publishedOn(), s.dateBasis(),
                        s.sentAt(), s.importedAt(), s.indexedAt(), s.indexState()))
                .toList();
    }
}

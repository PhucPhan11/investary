package com.investary.intelligence.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.investary.intelligence.application.BriefPeriods.Period;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.persistence.BriefJobQueue;
import com.investary.intelligence.persistence.BriefJobQueue.JobRequest;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.PortfolioView;

/**
 * Enqueues the scheduled briefs that are due. Called on every worker poll, so it is restart-safe: it looks
 * at the latest fire time of the cron (in the configured zone) and enqueues one job per portfolio (or one
 * general brief) with the idempotency key {@code scheduled:{scope}:{periodEnd}:{configVersion}}, inserted at
 * most once. Repeated ticks, restarts and concurrent instances therefore never duplicate a brief.
 * <p>
 * Missed runs: only the latest fire time is considered, and only while it is within the catch-up window;
 * older periods are generated on request.
 */
@Service
public class BriefScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefScheduler.class);

    private final BriefProperties properties;
    private final BriefJobQueue queue;
    private final PortfolioQueryService portfolios;
    private final BriefPeriods periods;
    private final Clock clock;

    BriefScheduler(BriefProperties properties, BriefJobQueue queue, PortfolioQueryService portfolios, Clock clock) {
        this.properties = properties;
        this.queue = queue;
        this.portfolios = portfolios;
        this.periods = new BriefPeriods(properties.schedule().cron(), properties.schedule().zoneId());
        this.clock = clock;
    }

    /**
     * @return number of jobs created by this call
     */
    public int enqueueDue() {
        if (!properties.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        ZonedDateTime fire = periods.latestFireAtOrBefore(now);
        if (fire == null || Duration.between(fire.toInstant(), now).compareTo(properties.catchUpWindow()) > 0) {
            return 0;
        }
        Period period = periods.scheduled(fire);
        String config = properties.configVersion();
        List<UUID> scopes = new ArrayList<>(portfolios.portfolios().stream()
                .sorted(Comparator.comparing(PortfolioView::createdAt).thenComparing(PortfolioView::id))
                .limit(properties.maxBriefsPerRun()).map(PortfolioView::id).toList());
        if (scopes.isEmpty()) {
            scopes.add(null);
        }
        int created = 0;
        for (UUID scope : scopes) {
            JobRequest request = new JobRequest(BriefTrigger.SCHEDULED, scope, period.start(), period.end(), period.zone(),
                    period.businessDate(), false, null, config);
            String key = "scheduled:" + (scope == null ? "general" : scope) + ":" + period.end() + ":" + config;
            if (queue.enqueue(request, key, properties.maxAttempts(), false).created()) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Enqueued {} scheduled brief(s) for the period ending {}", created, period.end());
        }
        return created;
    }
}

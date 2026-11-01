package com.investary.automation.application.center;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.investary.automation.application.settings.AutomationSettings;
import com.investary.automation.persistence.AutomationStatsStore;
import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;
import com.investary.intelligence.application.BriefSettings;
import com.investary.knowledge.usage.AiOperation;
import com.investary.knowledge.usage.AiSettings;
import com.investary.knowledge.usage.AiUsageService;
import com.investary.knowledge.usage.AiUsageService.UsageRow;

/**
 * AI usage report: provider requests, failures and provider-reported tokens per day or week, brief generations
 * and analyses, and today's use against the configured limits. No monetary cost is computed (no pricing source).
 */
@Service
public class AiUsageReportService {

    private final AiUsageService usage;
    private final AutomationStatsStore stats;
    private final AutomationSettings automation;
    private final BriefSettings briefs;
    private final AiSettings ai;

    public AiUsageReportService(AiUsageService usage, AutomationStatsStore stats, AutomationSettings automation, BriefSettings briefs,
                                AiSettings ai) {
        this.usage = usage;
        this.stats = stats;
        this.automation = automation;
        this.briefs = briefs;
        this.ai = ai;
    }

    /**
     * @param period {@code day} or {@code week} buckets
     * @param days   how many days back from today (1–90)
     */
    public UsageView report(String period, int days) {
        boolean weekly = "week".equals(period);
        if (!weekly && !"day".equals(period)) {
            throw new InvalidUsageRequestException("Period must be day or week");
        }
        if (days < 1 || days > 90) {
            throw new InvalidUsageRequestException("Days must be between 1 and 90");
        }
        LocalDate today = usage.today();
        LocalDate from = today.minusDays(days - 1L);
        Function<LocalDate, LocalDate> bucketOf = weekly ? d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : d -> d;

        Map<LocalDate, BucketBuilder> buckets = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            buckets.computeIfAbsent(bucketOf.apply(day), BucketBuilder::new);
        }
        Map<String, PurposeTotal> byPurpose = new LinkedHashMap<>();
        for (UsageRow row : usage.daily(from, today)) {
            BucketBuilder bucket = buckets.get(bucketOf.apply(row.date()));
            if (row.operation() == AiOperation.CHAT) {
                bucket.chatRequests += row.requests();
                bucket.chatFailures += row.failures();
            } else {
                bucket.embeddingRequests += row.requests();
                bucket.embeddingFailures += row.failures();
            }
            bucket.inputTokens += row.inputTokens();
            bucket.outputTokens += row.outputTokens();
            String key = row.operation() + "|" + row.purpose() + "|" + row.model();
            PurposeTotal total = byPurpose.getOrDefault(key, new PurposeTotal(row.operation().name(), row.purpose().name(), row.model(),
                    0, 0, 0, 0));
            byPurpose.put(key, new PurposeTotal(total.operation(), total.purpose(), total.model(), total.requests() + row.requests(),
                    total.failures() + row.failures(), total.inputTokens() + row.inputTokens(), total.outputTokens() + row.outputTokens()));
        }
        add(buckets, bucketOf, stats.briefsPerDay(from, today, false), (b, n) -> b.briefGenerations += n);
        add(buckets, bucketOf, stats.briefsPerDay(from, today, true), (b, n) -> b.aiBriefGenerations += n);
        add(buckets, bucketOf, stats.assessmentsPerDay(from, today, false), (b, n) -> b.analyses += n);
        add(buckets, bucketOf, stats.assessmentsPerDay(from, today, true), (b, n) -> b.automaticAnalyses += n);

        long automaticToday = stats.assessmentsPerDay(today, today, true).getOrDefault(today, 0L);
        long aiBriefsToday = stats.briefsPerDay(today, today, true).getOrDefault(today, 0L);
        Limits limits = new Limits(automation.workflow().maxAutoAssessmentsPerDay(), automaticToday, briefs.maxAiBriefsPerDay(), aiBriefsToday,
                ai.dailyAutomaticTokenBudget().orElse(null), usage.automaticTokensToday(), ai.maxConcurrentChat(), ai.maxConcurrentEmbedding());

        List<Bucket> rows = new ArrayList<>();
        buckets.values().forEach(b -> rows.add(b.build()));
        return new UsageView(weekly ? "week" : "day", from, today, usage.firstRecordedDay(), rows, List.copyOf(byPurpose.values()), limits);
    }

    private static void add(Map<LocalDate, BucketBuilder> buckets, Function<LocalDate, LocalDate> bucketOf, Map<LocalDate, Long> counts,
                            ObjLongConsumer<BucketBuilder> target) {
        counts.forEach((day, n) -> {
            BucketBuilder bucket = buckets.get(bucketOf.apply(day));
            if (bucket != null) {
                target.accept(bucket, n);
            }
        });
    }

    private static final class BucketBuilder {
        final LocalDate start;
        long chatRequests;
        long chatFailures;
        long embeddingRequests;
        long embeddingFailures;
        long inputTokens;
        long outputTokens;
        long briefGenerations;
        long aiBriefGenerations;
        long analyses;
        long automaticAnalyses;

        BucketBuilder(LocalDate start) {
            this.start = start;
        }

        Bucket build() {
            return new Bucket(start, chatRequests, chatFailures, embeddingRequests, embeddingFailures, inputTokens, outputTokens,
                    briefGenerations, aiBriefGenerations, analyses, automaticAnalyses);
        }
    }

    /**
     * @param start              first day of the bucket (a Monday for weekly buckets)
     * @param aiBriefGenerations briefs whose model part ran (answered or failed)
     * @param automaticAnalyses  impact assessments started by a workflow
     */
    public record Bucket(LocalDate start, long chatRequests, long chatFailures, long embeddingRequests, long embeddingFailures,
                         long inputTokens, long outputTokens, long briefGenerations, long aiBriefGenerations, long analyses,
                         long automaticAnalyses) {
    }

    public record PurposeTotal(String operation, String purpose, String model, long requests, long failures, long inputTokens,
                               long outputTokens) {
    }

    /**
     * @param dailyTokenBudget null when no budget is set
     */
    public record Limits(int maxAutoAssessmentsPerDay, long automaticAssessmentsToday, int maxAiBriefsPerDay, long aiBriefsToday,
                         Long dailyTokenBudget, long automaticTokensToday, int maxConcurrentChat, int maxConcurrentEmbedding) {
    }

    /**
     * @param trackingSince first day with recorded requests; earlier activity has no request counters
     */
    public record UsageView(String period, LocalDate from, LocalDate to, LocalDate trackingSince, List<Bucket> buckets,
                            List<PurposeTotal> byPurpose, Limits limits) {
    }

    static class InvalidUsageRequestException extends ApiException {
        InvalidUsageRequestException(String detail) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Invalid usage request", detail);
        }
    }
}

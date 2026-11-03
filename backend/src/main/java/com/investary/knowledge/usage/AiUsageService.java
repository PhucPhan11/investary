package com.investary.knowledge.usage;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Daily counters of AI provider calls ({@code ai_usage_daily}). Calls are recorded after they finish, in a
 * statement of their own; a failed recording is logged and never fails the call. Only provider-reported token
 * counts are stored; nothing is estimated and no cost is computed.
 */
@Service
public class AiUsageService {

    /** Usage days follow the market's day, like the daily analysis cap. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AiUsageService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /**
     * Records one provider request.
     *
     * @param inputTokens  provider-reported prompt tokens, or null
     * @param outputTokens provider-reported completion tokens, or null
     */
    public void record(AiOperation operation, AiPurpose purpose, String model, boolean success, Integer inputTokens, Integer outputTokens) {
        try {
            jdbc.update("""
                    insert into ai_usage_daily (usage_date, operation, purpose, model, requests, failures, input_tokens, output_tokens, updated_at)
                    values (:date, :operation, :purpose, :model, 1, :failures, :input, :output, now())
                    on conflict (usage_date, operation, purpose, model) do update set
                        requests = ai_usage_daily.requests + 1,
                        failures = ai_usage_daily.failures + excluded.failures,
                        input_tokens = ai_usage_daily.input_tokens + excluded.input_tokens,
                        output_tokens = ai_usage_daily.output_tokens + excluded.output_tokens,
                        updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("date", Date.valueOf(today()))
                    .addValue("operation", operation.name())
                    .addValue("purpose", purpose.name())
                    .addValue("model", model == null || model.isBlank() ? "unknown" : truncate(model))
                    .addValue("failures", success ? 0 : 1)
                    .addValue("input", inputTokens == null ? 0 : Math.max(0, inputTokens))
                    .addValue("output", outputTokens == null ? 0 : Math.max(0, outputTokens)));
        } catch (RuntimeException e) {
            log.warn("AI usage could not be recorded operation={} purpose={}: {}", operation, purpose, e.toString());
        }
    }

    /** Provider-reported tokens of automatic calls (assessments, briefs) today. */
    public long automaticTokensToday() {
        List<String> purposes = Arrays.stream(AiPurpose.values()).filter(AiPurpose::automatic).map(Enum::name).toList();
        Long total = jdbc.queryForObject("""
                select coalesce(sum(input_tokens + output_tokens), 0) from ai_usage_daily
                where usage_date = :date and purpose in (:purposes)
                """, new MapSqlParameterSource("date", Date.valueOf(today())).addValue("purposes", purposes), Long.class);
        return total == null ? 0 : total;
    }

    /** Counters per day, operation, purpose and model in [{@code from}, {@code to}], oldest day first. */
    public List<UsageRow> daily(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select usage_date, operation, purpose, model, requests, failures, input_tokens, output_tokens from ai_usage_daily
                where usage_date between :from and :to
                order by usage_date, operation, purpose, model
                """, new MapSqlParameterSource("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)),
                (rs, i) -> new UsageRow(rs.getDate("usage_date").toLocalDate(), AiOperation.valueOf(rs.getString("operation")),
                        AiPurpose.valueOf(rs.getString("purpose")), rs.getString("model"), rs.getLong("requests"), rs.getLong("failures"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens")));
    }

    /** First day with any recorded usage, or null when nothing was recorded yet. */
    public LocalDate firstRecordedDay() {
        Date first = jdbc.queryForObject("select min(usage_date) from ai_usage_daily", new MapSqlParameterSource(), Date.class);
        return first == null ? null : first.toLocalDate();
    }

    private static String truncate(String model) {
        return model.length() > 200 ? model.substring(0, 200) : model;
    }

    public record UsageRow(LocalDate date, AiOperation operation, AiPurpose purpose, String model, long requests, long failures,
                           long inputTokens, long outputTokens) {
    }
}

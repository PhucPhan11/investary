package com.investary.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;
import com.investary.automation.application.notification.NotificationTriggers;
import com.investary.automation.application.workflow.WorkflowJobProcessor;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.common.settings.RuntimeSettings;
import com.investary.common.settings.SettingsChangedEvent;
import com.investary.email.TestEmails;
import com.investary.email.application.EmailImportService;
import com.investary.email.domain.SourceType;
import com.investary.intelligence.application.BriefGeneratedEvent;
import com.investary.intelligence.application.BriefJobFailedEvent;
import com.investary.intelligence.application.ImpactAssessmentCreatedEvent;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.ImpactCategory;
import com.investary.intelligence.domain.ResearchState;
import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.FakeAiConfiguration;
import com.investary.knowledge.FakeEmbeddingModel;
import com.investary.knowledge.ScriptedChatModel;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.knowledge.usage.AiSettings;
import com.investary.portfolio.application.InstrumentService;
import com.investary.portfolio.application.PortfolioCommands.InstrumentCommand;
import com.investary.portfolio.application.PortfolioCommands.PortfolioCommand;
import com.investary.portfolio.application.PortfolioCommands.TransactionCommand;
import com.investary.portfolio.application.PortfolioService;
import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;
import com.investary.portfolio.domain.TransactionType;
import com.investary.research.download.DownloadJobProcessor;
import com.investary.research.download.DownloadJobQueue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 5 / Milestone 4: Automation Center APIs against real records. Settings persistence and validation,
 * backend AI limits, usage counters, overview metrics, job retry safety and notifications with deduplication.
 * Synthetic data only; the fake embedding and scripted chat models stand in for providers.
 */
@SpringBootTest(properties = "investary.workflow.max-manual-retries=3")
@AutoConfigureMockMvc
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class AutomationCenterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    static final Path STORAGE = createTempDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("investary.storage.root", () -> STORAGE.resolve("documents").toString());
        registry.add("investary.email.storage.root", () -> STORAGE.resolve("emails").toString());
        registry.add("investary.download.temp-dir", () -> STORAGE.resolve("downloads").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmailImportService emailImport;

    @Autowired
    AutomationJobQueue jobs;

    @Autowired
    WorkflowJobProcessor processor;

    @Autowired
    DownloadJobQueue downloadQueue;

    @Autowired
    DownloadJobProcessor downloadProcessor;

    @Autowired
    KnowledgeIndexQueue indexQueue;

    @Autowired
    KnowledgeIndexProcessor indexProcessor;

    @Autowired
    ScriptedChatModel chatModel;

    @Autowired
    FakeEmbeddingModel embeddingModel;

    @Autowired
    PortfolioService portfolioService;

    @Autowired
    InstrumentService instrumentService;

    @Autowired
    RuntimeSettings runtimeSettings;

    @Autowired
    AiSettings aiSettings;

    @Autowired
    AiConcurrencyLimits limits;

    @Autowired
    NotificationTriggers triggers;

    @Autowired
    ApplicationEventPublisher events;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    private WorkflowTestSupport support;
    private UUID portfolio;
    private UUID imp;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from notifications");
        jdbc.update("delete from app_settings");
        jdbc.update("delete from ai_usage_daily");
        jdbc.update("delete from brief_jobs");
        jdbc.update("delete from gmail_accounts");
        jdbc.update("delete from email_source_rules");
        jdbc.update("delete from portfolios");
        jdbc.update("delete from instruments");
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        jdbc.update("update workflow_settings set activated_at = now() - interval '1 day'");
        runtimeSettings.invalidate();
        aiSettings.onChanged(new SettingsChangedEvent(AiSettings.SECTION));
        chatModel.reset();
        chatModel.reply("{\"overall\": \"Nhận xét: cần đọc thêm nguồn.\", \"items\": []}");
        embeddingModel.failNext(0);
        support = new WorkflowTestSupport(jobs, processor, downloadQueue, downloadProcessor, indexQueue, indexProcessor, jdbc);

        portfolio = portfolioService.create(new PortfolioCommand("Danh mục thử", null, null)).id();
        imp = instrumentService.create(new InstrumentCommand("IMP", "Imexpharm", InstrumentType.STOCK, Exchange.HOSE, null, List.of())).id();
        portfolioService.addTransaction(portfolio, new TransactionCommand(imp, TransactionType.BUY, today.minusDays(30), new BigDecimal("100"),
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    // ---- settings ----

    @Test
    void settingsArePersistedValidatedVersionedAndResettable() throws Exception {
        JsonNode sections = getJson("/api/v1/automation/settings");
        List<String> names = new ArrayList<>();
        sections.forEach(s -> names.add(s.path("section").asString()));
        assertThat(names).containsExactly("gmail", "processing", "brief", "ai");
        JsonNode interval = field(section("gmail"), "intervalMinutes");
        assertThat(interval.path("value").asLong()).isEqualTo(30);
        assertThat(interval.path("overridden").asBoolean()).isFalse();
        assertThat(field(section("processing"), "workerConcurrency").path("appliesAfterRestart").asBoolean()).isTrue();

        JsonNode saved = putSettings("gmail", 0, "{\"intervalMinutes\": 15, \"pollingEnabled\": false}");
        assertThat(field(saved, "intervalMinutes").path("value").asLong()).isEqualTo(15);
        assertThat(field(saved, "intervalMinutes").path("overridden").asBoolean()).isTrue();
        assertThat(saved.path("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select value->>'intervalMinutes' from app_settings where section = 'gmail'", String.class))
                .isEqualTo("15");
        // The Gmail status reads the effective values at once (no restart).
        mvc.perform(get("/api/v1/integrations/gmail/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.syncIntervalMinutes").value(15))
                .andExpect(jsonPath("$.schedulerEnabled").value(false));

        // A stale version is rejected instead of overwriting the newer change.
        mvc.perform(put("/api/v1/automation/settings/gmail").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0, \"values\": {\"intervalMinutes\": 20}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_CONFLICT"));

        // Every field is validated in the backend.
        mvc.perform(put("/api/v1/automation/settings/gmail").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"values\": {\"intervalMinutes\": 2, \"schedulerConfigured\": false, \"unknown\": 1}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_SETTINGS"))
                .andExpect(jsonPath("$.errors.intervalMinutes").value("must be between 5 and 1440"))
                .andExpect(jsonPath("$.errors.schedulerConfigured").exists())
                .andExpect(jsonPath("$.errors.unknown").exists());
        mvc.perform(put("/api/v1/automation/settings/brief").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0, \"values\": {\"zone\": \"Mars/Base\", \"scheduleTime\": \"25:00\", \"defaultPortfolioId\": \""
                                + UUID.randomUUID() + "\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.zone").exists())
                .andExpect(jsonPath("$.errors.scheduleTime").exists());
        mvc.perform(put("/api/v1/automation/settings/brief").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0, \"values\": {\"defaultPortfolioId\": \"" + UUID.randomUUID() + "\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.defaultPortfolioId").value("is not an existing portfolio"));
        JsonNode brief = putSettings("brief", 0, "{\"scheduleTime\": \"6:30\", \"zone\": \"Europe/Berlin\", \"defaultPortfolioId\": \""
                + portfolio + "\"}");
        assertThat(field(brief, "scheduleTime").path("value").asString()).isEqualTo("06:30");

        // Reset returns the configured default.
        String body = mvc.perform(delete("/api/v1/automation/settings/gmail/intervalMinutes")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode reset = json.readTree(body);
        assertThat(field(reset, "intervalMinutes").path("value").asLong()).isEqualTo(30);
        assertThat(field(reset, "intervalMinutes").path("overridden").asBoolean()).isFalse();
        assertThat(field(reset, "pollingEnabled").path("overridden").asBoolean()).isTrue();
    }

    @Test
    void concurrencyLimitsAreResizedWhenSaved() throws Exception {
        putSettings("ai", 0, "{\"maxConcurrentChat\": 5, \"maxConcurrentEmbedding\": 3}");
        assertThat(limits.chatLimit()).isEqualTo(5);
        assertThat(limits.embeddingLimit()).isEqualTo(3);
        assertThat(limits.availableChatPermits()).isEqualTo(5);
        mvc.perform(put("/api/v1/automation/settings/ai").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"values\": {\"maxConcurrentChat\": 0}}"))
                .andExpect(status().isUnprocessableContent());
    }

    // ---- AI limits and usage ----

    @Test
    void theDailyAnalysisLimitIsEnforcedByTheBackend() throws Exception {
        putSettings("ai", 0, "{\"maxAutoAssessmentsPerDay\": 0}");
        UUID email = importImpEmail("limit");
        support.runAll();

        JsonNode workflow = getJson("/api/v1/automation/workflows/" + email);
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("reason").asString()).isEqualTo("DAILY_LIMIT_REACHED");
        assertThat(count("select count(*) from impact_assessments")).isZero();
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void theDailyTokenBudgetStopsAutomaticAssessments() throws Exception {
        jdbc.update("""
                insert into ai_usage_daily (usage_date, operation, purpose, model, requests, input_tokens, output_tokens)
                values (?, 'CHAT', 'BRIEF', 'test', 1, 4000, 1500)""", java.sql.Date.valueOf(today));
        putSettings("ai", 0, "{\"dailyAutomaticTokenBudget\": 5000}");
        UUID email = importImpEmail("budget");
        support.runAll();

        JsonNode workflow = getJson("/api/v1/automation/workflows/" + email);
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("reason").asString()).isEqualTo("TOKEN_BUDGET_REACHED");
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void usageIsRecordedPerCallAndReported() throws Exception {
        importImpEmail("usage");
        support.runAll();

        assertThat(count("select coalesce(sum(requests), 0) from ai_usage_daily where operation = 'CHAT' and purpose = 'ASSESSMENT'"))
                .isEqualTo(1);
        assertThat(count("select coalesce(sum(requests), 0) from ai_usage_daily where operation = 'EMBEDDING' and purpose = 'INDEXING'"))
                .isPositive();

        JsonNode usage = getJson("/api/v1/automation/usage?period=day&days=7");
        assertThat(usage.path("buckets")).hasSize(7);
        JsonNode todayBucket = usage.path("buckets").get(6);
        assertThat(todayBucket.path("start").asString()).isEqualTo(today.toString());
        assertThat(todayBucket.path("chatRequests").asLong()).isEqualTo(1);
        assertThat(todayBucket.path("embeddingRequests").asLong()).isPositive();
        assertThat(todayBucket.path("analyses").asLong()).isEqualTo(1);
        assertThat(todayBucket.path("automaticAnalyses").asLong()).isEqualTo(1);
        assertThat(usage.path("limits").path("automaticAssessmentsToday").asLong()).isEqualTo(1);
        assertThat(usage.path("limits").path("maxAutoAssessmentsPerDay").asInt()).isEqualTo(20);
        assertThat(usage.path("trackingSince").asString()).isEqualTo(today.toString());

        JsonNode weekly = getJson("/api/v1/automation/usage?period=week&days=14");
        assertThat(weekly.path("period").asString()).isEqualTo("week");
        mvc.perform(get("/api/v1/automation/usage").param("days", "500")).andExpect(status().isBadRequest());
    }

    // ---- overview ----

    @Test
    void overviewMetricsAgreeWithTheRecordsOfTheWindow() throws Exception {
        importImpEmail("overview");
        support.runAll();
        UUID old = emailImport.importRaw(TestEmails.builder().subject("Old research").text("Nothing new.").build(),
                SourceType.EML_UPLOAD, "old.eml");
        jdbc.update("update source_messages set imported_at = now() - interval '10 days' where id = ?", old);

        JsonNode day = getJson("/api/v1/automation/overview?period=24h");
        assertThat(day.path("metrics").path("emailsImported").asLong())
                .isEqualTo(count("select count(*) from source_messages where imported_at >= now() - interval '24 hours'"))
                .isEqualTo(1);
        assertThat(day.path("metrics").path("emailsProcessed").asLong()).isEqualTo(1);
        assertThat(day.path("metrics").path("assessmentsCreated").asLong())
                .isEqualTo(count("select count(*) from impact_assessments"));
        assertThat(day.path("metrics").path("pendingJobs").asLong())
                .isEqualTo(count("select count(*) from automation_jobs where status in ('PENDING', 'RUNNING', 'WAITING')"));
        assertThat(Instant.parse(day.path("to").asString())).isAfter(Instant.parse(day.path("from").asString()));
        assertThat(getJson("/api/v1/automation/overview?period=30d").path("metrics").path("emailsImported").asLong()).isEqualTo(2);

        // Workers are off in tests and Gmail is not configured: nothing may claim to be operational.
        List<String> states = new ArrayList<>();
        day.path("health").forEach(h -> states.add(h.path("component").asString() + "=" + h.path("state").asString()));
        assertThat(states).contains("GMAIL=UNAVAILABLE", "RESEARCH_INGESTION=DISABLED", "DOCUMENT_PROCESSING=DISABLED",
                "BRIEF_GENERATION=DISABLED");
        assertThat(states).noneMatch(s -> s.endsWith("=OPERATIONAL"));

        mvc.perform(get("/api/v1/automation/overview").param("period", "1y")).andExpect(status().isBadRequest());
    }

    // ---- jobs and retry ----

    @Test
    void aFailedJobIsRetriedOnceAtATimeWithItsHistoryAndWithoutReimporting() throws Exception {
        embeddingModel.failNext(10_000);
        UUID email = importImpEmail("retry");
        support.runAll();
        JsonNode failed = job(getJson("/api/v1/automation/workflows/" + email), "INDEX");
        String jobId = failed.path("id").asString();
        assertThat(count("select count(*) from notifications where type = 'PROCESSING_FAILED'")).isEqualTo(1);

        JsonNode detail = getJson("/api/v1/automation/jobs/" + jobId);
        assertThat(detail.path("job").path("retryable").asBoolean()).isTrue();
        assertThat(detail.path("job").path("sourceTitle").asString()).startsWith("IMP cập nhật");
        assertThat(detail.path("workflow").path("stages").size()).isGreaterThan(3);
        assertThat(detail.path("attempts").get(0).path("result").asString()).isIn("RETRY_SCHEDULED", "FAILED");
        assertThat(detail.path("attempts").get(detail.path("attempts").size() - 1).path("result").asString()).isEqualTo("FAILED");

        // Two retries at the same time: exactly one is accepted.
        embeddingModel.failNext(0);
        List<Integer> statuses = concurrently(2, () -> mvc.perform(post("/api/v1/automation/jobs/{id}/retry", jobId))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        support.runAll();

        assertThat(getJson("/api/v1/automation/workflows/" + email).path("status").asString()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from source_messages")).isEqualTo(1);
        JsonNode after = getJson("/api/v1/automation/jobs/" + jobId);
        List<String> results = new ArrayList<>();
        after.path("attempts").forEach(a -> results.add(a.path("trigger").asString() + ":" + a.path("result").asString()));
        assertThat(results).contains("MANUAL_RETRY:RETRY_REQUESTED", "AUTO:SUCCEEDED");
        assertThat(after.path("job").path("retryable").asBoolean()).isFalse();
        assertThat(after.path("job").path("manualRetries").asInt()).isEqualTo(1);
        mvc.perform(post("/api/v1/automation/jobs/{id}/retry", jobId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_NOT_RETRYABLE"));
        // The failure notified once; the retry did not add another.
        assertThat(count("select count(*) from notifications where type = 'PROCESSING_FAILED'")).isEqualTo(1);
    }

    @Test
    void manualRetriesAreCapped() throws Exception {
        embeddingModel.failNext(100_000);
        UUID email = importImpEmail("cap");
        support.runAll();
        String jobId = job(getJson("/api/v1/automation/workflows/" + email), "INDEX").path("id").asString();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/automation/jobs/{id}/retry", jobId)).andExpect(status().isAccepted());
            support.runAll();
        }
        assertThat(getJson("/api/v1/automation/jobs/" + jobId).path("job").path("retryBlockedReason").asString())
                .isEqualTo("RETRY_LIMIT_REACHED");
        mvc.perform(post("/api/v1/automation/jobs/{id}/retry", jobId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETRY_LIMIT_REACHED"));
        assertThat(count("select count(*) from notifications where type = 'PROCESSING_FAILED'")).isEqualTo(1);
    }

    @Test
    void jobsCanBeFilteredByDateAndSource() throws Exception {
        importImpEmail("filters");
        support.runAll();
        long all = getJson("/api/v1/automation/jobs?size=100").path("totalElements").asLong();
        assertThat(all).isPositive();
        assertThat(getJson("/api/v1/automation/jobs?sourceType=EMAIL&from=" + today + "&to=" + today).path("totalElements").asLong())
                .isEqualTo(all);
        assertThat(getJson("/api/v1/automation/jobs?sourceType=DOCUMENT").path("totalElements").asLong()).isZero();
        assertThat(getJson("/api/v1/automation/jobs?to=" + today.minusDays(2)).path("totalElements").asLong()).isZero();
        assertThat(getJson("/api/v1/automation/jobs?ruleId=" + UUID.randomUUID()).path("totalElements").asLong()).isZero();
    }

    @Test
    void failedBriefJobsAreListedAndRetriedOnce() throws Exception {
        String body = mvc.perform(post("/api/v1/briefs/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portfolioId\": \"" + portfolio + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = json.readTree(body).path("id").asString();
        jdbc.update("update brief_jobs set status = 'FAILED', error_code = 'INTERNAL_ERROR', completed_at = now() where id = ?::uuid", jobId);

        assertThat(getJson("/api/v1/briefs/jobs?status=FAILED").path("totalElements").asLong()).isEqualTo(1);
        mvc.perform(post("/api/v1/briefs/jobs/{id}/retry", jobId)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/briefs/jobs/{id}/retry", jobId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_NOT_RETRYABLE"));
        assertThat(count("select count(*) from brief_jobs")).isEqualTo(1);
    }

    // ---- notifications ----

    @Test
    void portfolioResearchNotifiesOnceAndCanBeReadAndUnread() throws Exception {
        UUID email = importImpEmail("notify");
        support.runAll();
        mvc.perform(post("/api/v1/automation/workflows/{id}/process", email)).andExpect(status().isAccepted());
        support.runAll();

        assertThat(count("select count(*) from notifications where type = 'PORTFOLIO_RESEARCH'")).isEqualTo(1);
        JsonNode list = getJson("/api/v1/notifications?unreadOnly=true");
        JsonNode notification = list.path("content").get(0);
        assertThat(notification.path("title").asString()).isEqualTo("New research on IMP");
        assertThat(notification.path("entityType").asString()).isEqualTo("EMAIL");
        assertThat(notification.path("entityId").asString()).isEqualTo(email.toString());
        assertThat(notification.path("portfolioId").asString()).isEqualTo(portfolio.toString());
        assertThat(notification.path("message").asString()).contains("Danh mục thử");

        String id = notification.path("id").asString();
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.count").value(1));
        mvc.perform(patch("/api/v1/notifications/{id}/read", id)).andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.count").value(0));
        mvc.perform(patch("/api/v1/notifications/{id}/unread", id)).andExpect(jsonPath("$.read").value(false));
        mvc.perform(post("/api/v1/notifications/read-all")).andExpect(jsonPath("$.updated").value(1));
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.count").value(0));
        mvc.perform(patch("/api/v1/notifications/{id}/read", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void onlySupportedDirectRisksOfAutomaticAssessmentsNotify() {
        UUID source = UUID.randomUUID();
        UUID assessment = UUID.randomUUID();
        List<ImpactAssessmentCreatedEvent.Item> items = List.of(
                new ImpactAssessmentCreatedEvent.Item(imp, "IMP", ImpactCategory.DIRECT_HOLDING, ImpactCategory.POTENTIAL_RISK,
                        EvidenceStatus.SUPPORTED),
                new ImpactAssessmentCreatedEvent.Item(UUID.randomUUID(), "VNM", ImpactCategory.DIRECT_HOLDING, ImpactCategory.POTENTIAL_RISK,
                        EvidenceStatus.PARTIAL),
                new ImpactAssessmentCreatedEvent.Item(UUID.randomUUID(), "FPT", ImpactCategory.MONITORING_ONLY, ImpactCategory.POTENTIAL_RISK,
                        EvidenceStatus.SUPPORTED));
        events.publishEvent(new ImpactAssessmentCreatedEvent(assessment, portfolio, "EMAIL", source, items));
        events.publishEvent(new ImpactAssessmentCreatedEvent(UUID.randomUUID(), portfolio, "EMAIL", source, items));
        events.publishEvent(new ImpactAssessmentCreatedEvent(UUID.randomUUID(), portfolio, null, null, items));

        assertThat(jdbc.queryForList("select title from notifications where type = 'RESEARCH_RISK'", String.class))
                .containsExactly("Possible risk for IMP");
        assertThat(jdbc.queryForObject("select priority from notifications where type = 'RESEARCH_RISK'", String.class)).isEqualTo("HIGH");
    }

    @Test
    void briefEventsNotifyReadyConflictsAndFailuresWithoutDuplicates() {
        Instant periodEnd = Instant.parse("2026-11-01T01:00:00Z");
        UUID brief = UUID.randomUUID();
        List<BriefGeneratedEvent.Conflict> conflicts = List.of(
                new BriefGeneratedEvent.Conflict("IMP", true, List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("SSI", "VNDirect")),
                new BriefGeneratedEvent.Conflict("HPG", false, List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("SSI", "MBS")));
        BriefGeneratedEvent created = new BriefGeneratedEvent(brief, brief, 1, portfolio, BriefTrigger.SCHEDULED, BriefStatus.COMPLETED,
                ResearchState.NEW_RESEARCH, periodEnd, LocalDate.of(2026, 11, 1), conflicts);
        events.publishEvent(created);
        events.publishEvent(new BriefGeneratedEvent(UUID.randomUUID(), brief, 2, portfolio, BriefTrigger.MANUAL, BriefStatus.COMPLETED,
                ResearchState.NEW_RESEARCH, periodEnd, LocalDate.of(2026, 11, 1), conflicts));
        events.publishEvent(new BriefGeneratedEvent(UUID.randomUUID(), brief, 3, portfolio, BriefTrigger.REGENERATION, BriefStatus.COMPLETED,
                ResearchState.NEW_RESEARCH, periodEnd, LocalDate.of(2026, 11, 1), conflicts));
        UUID failedJob = UUID.randomUUID();
        events.publishEvent(new BriefJobFailedEvent(failedJob, portfolio, LocalDate.of(2026, 11, 1), "INTERNAL_ERROR"));
        events.publishEvent(new BriefJobFailedEvent(failedJob, portfolio, LocalDate.of(2026, 11, 1), "INTERNAL_ERROR"));

        assertThat(count("select count(*) from notifications where type = 'DAILY_BRIEF_READY'")).isEqualTo(1);
        assertThat(jdbc.queryForList("select title from notifications where type = 'RESEARCH_CONFLICT'", String.class))
                .containsExactly("Different views on IMP");
        assertThat(count("select count(*) from notifications where type = 'PROCESSING_FAILED' and entity_type = 'BRIEF_JOB'")).isEqualTo(1);
    }

    @Test
    void repeatedGmailFailuresNotifyOncePerStreak() {
        UUID account = UUID.randomUUID();
        jdbc.update("insert into gmail_accounts (id, email_address, status) values (?, 'investor@example.com', 'DISCONNECTED')", account);
        UUID first = failedRun(account, 50);
        triggers.syncFinished(account, first);
        UUID second = failedRun(account, 40);
        triggers.syncFinished(account, second);
        assertThat(count("select count(*) from notifications where type = 'SYNC_FAILED'")).isZero();

        UUID third = failedRun(account, 30);
        triggers.syncFinished(account, third);
        UUID fourth = failedRun(account, 20);
        triggers.syncFinished(account, fourth);
        assertThat(count("select count(*) from notifications where type = 'SYNC_FAILED'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select dedup_key from notifications where type = 'SYNC_FAILED'", String.class))
                .isEqualTo("sync-failed:" + account + ":" + first);

        UUID ok = UUID.randomUUID();
        jdbc.update("""
                insert into gmail_sync_runs (id, account_id, trigger_type, mode, status, started_at, finished_at, imported)
                values (?, ?, 'SCHEDULED', 'INCREMENTAL', 'SUCCEEDED', now() - interval '10 minutes', now(), 2)""", ok, account);
        triggers.syncFinished(account, ok);
        assertThat(jdbc.queryForList("select title from notifications where type = 'NEW_RESEARCH'", String.class))
                .containsExactly("2 new research emails");
    }

    // ---- helpers ----

    private UUID failedRun(UUID account, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into gmail_sync_runs (id, account_id, trigger_type, mode, status, started_at, finished_at, error_code)
                values (?, ?, 'SCHEDULED', 'INCREMENTAL', 'FAILED', now() - make_interval(mins => ?), now(), 'GOOGLE_UNAVAILABLE')""",
                id, account, minutesAgo);
        return id;
    }

    private UUID importImpEmail(String marker) {
        return emailImport.importRaw(TestEmails.builder().subject("IMP cập nhật " + marker)
                .text("Cổ phiếu IMP tăng trưởng doanh thu quý 3. " + marker).build(), SourceType.EML_UPLOAD, marker + ".eml");
    }

    private JsonNode putSettings(String section, int version, String values) throws Exception {
        String body = mvc.perform(put("/api/v1/automation/settings/" + section).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": " + version + ", \"values\": " + values + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode section(String name) throws Exception {
        return getJson("/api/v1/automation/settings/" + name);
    }

    private static JsonNode field(JsonNode section, String name) {
        for (JsonNode field : section.path("fields")) {
            if (name.equals(field.path("name").asString())) {
                return field;
            }
        }
        throw new AssertionError("No field " + name);
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode stage(JsonNode workflow, String name) {
        for (JsonNode stage : workflow.path("stages")) {
            if (name.equals(stage.path("stage").asString())) {
                return stage;
            }
        }
        throw new AssertionError("No stage " + name);
    }

    private static JsonNode job(JsonNode workflow, String type) {
        for (JsonNode job : workflow.path("jobs")) {
            if (type.equals(job.path("type").asString())) {
                return job;
            }
        }
        throw new AssertionError("No job " + type);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static <T> List<T> concurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-center-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

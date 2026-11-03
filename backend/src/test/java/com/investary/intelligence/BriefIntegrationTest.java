package com.investary.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;
import com.investary.document.application.DocumentService;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.document.domain.ReportType;
import com.investary.email.TestEmails;
import com.investary.email.application.EmailImportService;
import com.investary.intelligence.application.BriefJobProcessor;
import com.investary.intelligence.application.BriefScheduler;
import com.investary.intelligence.persistence.BriefJobQueue;
import com.investary.knowledge.FakeAiConfiguration;
import com.investary.knowledge.KnowledgeTestSupport;
import com.investary.knowledge.ScriptedChatModel;
import com.investary.knowledge.TestResearchPdfs;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.portfolio.application.InstrumentService;
import com.investary.portfolio.application.PortfolioCommands.InstrumentCommand;
import com.investary.portfolio.application.PortfolioCommands.PortfolioCommand;
import com.investary.portfolio.application.PortfolioCommands.TransactionCommand;
import com.investary.portfolio.application.PortfolioService;
import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;
import com.investary.portfolio.domain.TransactionType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 5 / Milestone 3 end to end: synthetic research is indexed with the fake embedding model, the time
 * each source became searchable is set explicitly, and briefs are generated through the job queue with the
 * scripted chat model (no real provider). The application clock is a test clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({FakeAiConfiguration.class, BriefIntegrationTest.ClockConfiguration.class})
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class BriefIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    static final Path STORAGE = createTempDir();

    private static final Pattern HOLDING = Pattern.compile("<holding symbol=\"([A-Z0-9]+)\" evidence=\"([S0-9,]+)\"/>");
    private static final Pattern SOURCE = Pattern.compile("<source id=\"(S\\d+)\"[^\\n]*>\\n(.*?)\\n</source>", Pattern.DOTALL);
    /** 09:00 in Ho Chi Minh City on 1 November 2026; the scheduled period is 31 Oct 08:00 – 1 Nov 08:00 local time. */
    private static final Instant NOW = Instant.parse("2026-11-01T02:00:00Z");
    private static final Instant PERIOD_START = Instant.parse("2026-10-31T01:00:00Z");
    private static final Instant IN_PERIOD = Instant.parse("2026-10-31T05:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("investary.storage.root", () -> STORAGE.resolve("documents").toString());
        registry.add("investary.email.storage.root", () -> STORAGE.resolve("emails").toString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    DocumentService documents;

    @Autowired
    EmailImportService emailImport;

    @Autowired
    KnowledgeIndexQueue indexQueue;

    @Autowired
    KnowledgeIndexProcessor indexProcessor;

    @Autowired
    ScriptedChatModel chatModel;

    @Autowired
    PortfolioService portfolioService;

    @Autowired
    InstrumentService instrumentService;

    @Autowired
    BriefScheduler scheduler;

    @Autowired
    BriefJobQueue briefJobs;

    @Autowired
    BriefJobProcessor briefProcessor;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, UUID> docs = new LinkedHashMap<>();
    private final Map<String, UUID> instruments = new LinkedHashMap<>();
    private UUID portfolio;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        jdbc.update("delete from brief_jobs");
        jdbc.update("delete from briefs");
        jdbc.update("delete from portfolios");
        jdbc.update("delete from instruments");
        jdbc.update("delete from investor_profile");
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        chatModel.reset();
        chatModel.reply(BriefIntegrationTest::briefReply);

        docs.put("fpt-tcbs", pdf("TCBS_FPT.pdf", "TCBS", "FPT", ReportType.STOCK_ANALYSIS, "2026-10-30", List.of(
                "FPT - Cập nhật kết quả kinh doanh (dữ liệu tổng hợp)",
                "Khuyến nghị: Mua.",
                "Lợi nhuận quý 3 của FPT tăng 25% nhờ mảng công nghệ.")));
        docs.put("fpt-ssi", pdf("SSI_FPT.pdf", "SSI", "FPT", ReportType.STOCK_ANALYSIS, "2026-10-31", List.of(
                "FPT - Định giá (dữ liệu tổng hợp)",
                "Khuyến nghị: Kém khả quan.",
                "Rủi ro định giá cao sau giai đoạn tăng mạnh.")));
        docs.put("market", pdf("VND_market.pdf", "VNDIRECT", null, ReportType.MARKET_OVERVIEW, "2026-10-31", List.of(
                "Nhận định thị trường (dữ liệu tổng hợp)",
                "Thanh khoản thị trường giảm trong tuần qua.",
                "Nhà đầu tư thận trọng trước kỳ họp chính sách.")));
        docs.put("vnm", pdf("VCBS_VNM.pdf", "VCBS", "VNM", ReportType.STOCK_ANALYSIS, "2026-10-29", List.of(
                "VNM - Cập nhật (dữ liệu tổng hợp)",
                "Doanh thu sữa nội địa phục hồi trong quý.")));
        docs.put("hpg-old", pdf("TCBS_HPG_old.pdf", "TCBS", "HPG", ReportType.STOCK_ANALYSIS, "2026-08-01", List.of(
                "HPG - Báo cáo quý 2 (dữ liệu tổng hợp)",
                "Sản lượng thép quý 2 tăng so với cùng kỳ.")));
        KnowledgeTestSupport.indexAll(indexQueue, indexProcessor);
        availableAt(IN_PERIOD, Instant.parse("2026-10-31T04:00:00Z"), docs.values().toArray(UUID[]::new));

        portfolio = portfolioService.create(new PortfolioCommand("Danh mục bản tin", null, null)).id();
        instruments.put("FPT", instrument("FPT", "FPT (tổng hợp)"));
        instruments.put("HPG", instrument("HPG", "Hòa Phát (tổng hợp)"));
        buy("FPT", "100", "100000");
        buy("HPG", "500", "25000");
    }

    @Test
    void scheduledBriefIsGeneratedOncePerPeriodWithValidatedCitations() throws Exception {
        assertThat(scheduler.enqueueDue()).isEqualTo(1);
        assertThat(scheduler.enqueueDue()).as("a second tick in the same period").isZero();
        runAll();

        JsonNode list = getJson("/api/v1/briefs?portfolioId=" + portfolio);
        assertThat(list.path("totalElements").asInt()).isEqualTo(1);
        JsonNode brief = getJson("/api/v1/briefs/" + list.path("content").get(0).path("id").asString());

        assertThat(brief.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(brief.path("triggerType").asString()).isEqualTo("SCHEDULED");
        assertThat(brief.path("researchState").asString()).isEqualTo("NEW_RESEARCH");
        assertThat(brief.path("zone").asString()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(Instant.parse(brief.path("periodStart").asString())).isEqualTo(PERIOD_START);
        assertThat(Instant.parse(brief.path("periodEnd").asString())).isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
        assertThat(brief.path("businessDate").asString()).isEqualTo("2026-11-01");
        assertThat(brief.path("aiStatus").asString()).isEqualTo("COMPLETED");
        assertThat(brief.path("model").asString()).isEqualTo("fake:scripted");

        // Every cited id exists among the stored sources, and every source of the period is there once.
        List<String> sourceIds = new ArrayList<>();
        brief.path("citations").forEach(c -> sourceIds.add(c.path("sourceId").asString()));
        assertThat(sourceIds).containsAll(docs.values().stream().map(UUID::toString).toList());
        List<String> labels = new ArrayList<>();
        brief.path("citations").forEach(c -> labels.add(c.path("label").asString()));
        sections(brief).forEach((key, section) -> section.path("items").forEach(item ->
                item.path("citations").forEach(label -> assertThat(labels).contains(label.asString()))));

        JsonNode portfolioSection = sections(brief).get("PORTFOLIO_UPDATES");
        assertThat(items(portfolioSection, "AI_INTERPRETATION")).anySatisfy(item -> {
            assertThat(item.path("symbol").asString()).isEqualTo("FPT");
            assertThat(item.path("evidenceStatus").asString()).isEqualTo("SUPPORTED");
        });
        assertThat(portfolioSection.toString()).doesNotContain("MWG");
        assertThat(items(portfolioSection, "SOURCE_CLAIM")).anySatisfy(item -> assertThat(item.path("brokerage").asString()).isEqualTo("SSI"));
        assertThat(sections(brief).get("RISKS_AND_CONFLICTS").toString()).contains("Quan điểm khác nhau về FPT").contains("Kém khả quan");
        assertThat(sections(brief).get("MARKET_OVERVIEW").toString()).contains("VNDIRECT");
        assertThat(sections(brief).get("OPPORTUNITIES").toString()).contains("VNM");
        assertThat(sections(brief).get("FOLLOW_UPS").toString()).doesNotContain("mua thêm").contains("Đọc báo cáo định giá FPT");

        // The invented S99 was removed; the old report is labelled as old, not new.
        assertThat(brief.path("limitations").toString()).contains("S99").contains("OLD_PUBLICATION");
        assertThat(citation(brief, docs.get("hpg-old")).path("oldPublication").asBoolean()).isTrue();
        assertThat(citation(brief, docs.get("fpt-tcbs")).path("oldPublication").asBoolean()).isFalse();
        assertThat(citation(brief, docs.get("fpt-tcbs")).path("indexedAt").asString()).isEqualTo(IN_PERIOD.toString());

        // One model call; instructions only in the system message, sources inside <source> elements.
        assertThat(chatModel.prompts()).hasSize(1);
        Prompt prompt = chatModel.lastPrompt();
        assertThat(prompt.getInstructions().getFirst().getText()).contains("JSON").doesNotContain("Lợi nhuận quý 3");
        assertThat(prompt.getInstructions().getLast().getText()).contains("<source id=\"S1\"").contains("<holding symbol=\"FPT\"");
    }

    @Test
    void concurrentSchedulerTicksCreateASingleJob() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> ticks = List.of(scheduler::enqueueDue, scheduler::enqueueDue, scheduler::enqueueDue, scheduler::enqueueDue);
            int created = 0;
            for (Future<Integer> result : pool.invokeAll(ticks)) {
                created += result.get();
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        runAll();
        assertThat(count("select count(*) from briefs where trigger_type = 'SCHEDULED'")).isEqualTo(1);
    }

    @Test
    void manualGenerationWithUnchangedInputsDoesNotCallTheModelAgain() throws Exception {
        JsonNode job = postJson("/api/v1/briefs/generate", "{\"portfolioId\": \"" + portfolio + "\", \"date\": \"2026-11-01\"}");
        assertThat(job.path("status").asString()).isEqualTo("PENDING");
        runAll();
        JsonNode done = getJson("/api/v1/briefs/jobs/" + job.path("id").asString());
        assertThat(done.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.path("outcome").asString()).isEqualTo("CREATED");
        String briefId = done.path("briefId").asString();

        JsonNode again = postJson("/api/v1/briefs/generate", "{\"portfolioId\": \"" + portfolio + "\", \"date\": \"2026-11-01\"}");
        runAll();
        JsonNode second = getJson("/api/v1/briefs/jobs/" + again.path("id").asString());

        assertThat(second.path("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(second.path("briefId").asString()).isEqualTo(briefId);
        assertThat(chatModel.prompts()).hasSize(1);
        assertThat(count("select count(*) from briefs")).isEqualTo(1);
    }

    @Test
    void regenerationAfterLateIndexingCreatesANewRevision() throws Exception {
        UUID first = generate("2026-11-01");
        // Imported before the period, but only searchable during it: a late arrival.
        UUID late = pdf("VCBS_FPT.pdf", "VCBS", "FPT", ReportType.STOCK_ANALYSIS, "2026-10-31", List.of(
                "FPT - Góc nhìn mới (dữ liệu tổng hợp)", "Mảng chuyển đổi số tiếp tục tăng trưởng."));
        KnowledgeTestSupport.indexAll(indexQueue, indexProcessor);
        availableAt(Instant.parse("2026-10-31T09:00:00Z"), Instant.parse("2026-10-30T09:00:00Z"), late);

        JsonNode job = postJson("/api/v1/briefs/" + first + "/regenerate", null);
        assertThat(job.path("triggerType").asString()).isEqualTo("REGENERATION");
        runAll();
        JsonNode done = getJson("/api/v1/briefs/jobs/" + job.path("id").asString());
        assertThat(done.path("outcome").asString()).isEqualTo("CREATED");
        JsonNode revision = getJson("/api/v1/briefs/" + done.path("briefId").asString());

        assertThat(revision.path("revision").asInt()).isEqualTo(2);
        assertThat(revision.path("previousRevisionId").asString()).isEqualTo(first.toString());
        assertThat(revision.path("revisions")).hasSize(2);
        assertThat(citation(revision, late).path("lateArrival").asBoolean()).isTrue();
        assertThat(revision.path("limitations").toString()).contains("LATE_ARRIVAL");
        // The first revision is kept unchanged, and the list shows only the latest one.
        JsonNode original = getJson("/api/v1/briefs/" + first);
        assertThat(original.path("revision").asInt()).isEqualTo(1);
        assertThat(original.toString()).doesNotContain(late.toString());
        assertThat(getJson("/api/v1/briefs?portfolioId=" + portfolio).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void sourceIndexedAfterThePeriodBelongsToTheNextBrief() throws Exception {
        UUID late = pdf("SSI_HPG.pdf", "SSI", "HPG", ReportType.STOCK_ANALYSIS, "2026-11-01", List.of(
                "HPG - Cập nhật (dữ liệu tổng hợp)", "Giá thép xây dựng ổn định."));
        KnowledgeTestSupport.indexAll(indexQueue, indexProcessor);
        // Imported 07:50 local time, searchable 08:10: after the period of 1 November ended.
        availableAt(Instant.parse("2026-11-01T01:10:00Z"), Instant.parse("2026-11-01T00:50:00Z"), late);

        JsonNode november1 = getJson("/api/v1/briefs/" + generate("2026-11-01"));
        assertThat(november1.toString()).doesNotContain(late.toString());

        clock.set(Instant.parse("2026-11-02T02:00:00Z"));
        JsonNode november2 = getJson("/api/v1/briefs/" + generate("2026-11-02"));
        assertThat(citation(november2, late).path("lateArrival").asBoolean()).isTrue();
        assertThat(november2.path("citations")).hasSize(1);
    }

    @Test
    void periodsWithoutNewResearchSayWhetherSourcesArePending() throws Exception {
        JsonNode empty = getJson("/api/v1/briefs/" + generate("2026-10-20"));
        assertThat(empty.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(empty.path("researchState").asString()).isEqualTo("NO_MATERIAL_RESEARCH");
        assertThat(empty.path("aiStatus").asString()).isEqualTo("SKIPPED_NO_EVIDENCE");
        assertThat(sections(empty).get("EXECUTIVE_SUMMARY").toString()).contains("không có nghiên cứu mới đáng kể");
        assertThat(chatModel.prompts()).isEmpty();

        // A report imported on 24 October that is not indexed yet.
        UUID waiting = pdf("TCBS_FPT_waiting.pdf", "TCBS", "FPT", ReportType.STOCK_ANALYSIS, "2026-10-24", List.of(
                "FPT - Báo cáo chờ xử lý (dữ liệu tổng hợp)", "Nội dung chưa được lập chỉ mục."));
        jdbc.update("update document set uploaded_at = ? where id = ?", Timestamp.from(Instant.parse("2026-10-24T05:00:00Z")), waiting);
        JsonNode pending = getJson("/api/v1/briefs/" + generate("2026-10-25"));

        assertThat(pending.path("researchState").asString()).isEqualTo("PENDING_ONLY");
        assertThat(pending.path("pendingSources")).hasSize(1);
        assertThat(pending.path("pendingSources").get(0).path("sourceId").asString()).isEqualTo(waiting.toString());
        assertThat(pending.path("pendingSources").get(0).path("state").asString()).isIn("PENDING", "NOT_TRACKED");
        assertThat(pending.path("limitations").toString()).contains("PENDING_SOURCES");
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void emailWithAttachedReportIsCountedOnce() throws Exception {
        byte[] attachment = TestResearchPdfs.textPdf(List.of(List.of("FPT - Báo cáo đính kèm (dữ liệu tổng hợp)",
                "Biên lợi nhuận mảng phần mềm cải thiện.")));
        UUID email = emailImport.importEml(new MockMultipartFile("file", "fpt.eml", "message/rfc822", TestEmails.builder()
                .from("TCBS Research <research@tcbs.com.vn>").subject("FPT - báo cáo đính kèm").sentAt(Instant.parse("2026-10-31T03:00:00Z"))
                .html("<html><body><p>Gửi quý khách báo cáo FPT đính kèm.</p></body></html>")
                .attachment("FPT_attached.pdf", "application/pdf", attachment).build()));
        KnowledgeTestSupport.indexAll(indexQueue, indexProcessor);
        UUID attached = jdbc.queryForObject("select document_id from message_documents where source_message_id = ?", UUID.class, email);
        jdbc.update("update source_messages set imported_at = ? where id = ?", Timestamp.from(Instant.parse("2026-10-31T04:00:00Z")), email);
        jdbc.update("update knowledge_sources set first_indexed_at = ? where source_message_id = ?", Timestamp.from(IN_PERIOD), email);
        availableAt(IN_PERIOD, Instant.parse("2026-10-31T04:00:00Z"), attached);

        JsonNode brief = getJson("/api/v1/briefs/" + generate("2026-11-01"));

        List<String> sourceIds = new ArrayList<>();
        brief.path("citations").forEach(c -> sourceIds.add(c.path("sourceId").asString()));
        assertThat(sourceIds).contains(attached.toString()).doesNotContain(email.toString());
        assertThat(citation(brief, attached).path("receivedVia").toString()).contains(email.toString());
    }

    @Test
    void providerFailureGivesAPartialBriefWithTheDeterministicSections() throws Exception {
        chatModel.reply(prompt -> {
            throw new NonTransientAiException("model not found");
        });
        JsonNode brief = getJson("/api/v1/briefs/" + generate("2026-11-01"));

        assertThat(brief.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(brief.path("aiStatus").asString()).isEqualTo("FAILED");
        assertThat(brief.path("errorCode").asString()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(sections(brief).get("PORTFOLIO_UPDATES").toString()).contains("SOURCE_CLAIM").doesNotContain("AI_INTERPRETATION");
        assertThat(brief.path("errorSummary").asString()).doesNotContain("http");
    }

    @Test
    void interruptedJobIsRecoveredAfterItsLease() throws Exception {
        JsonNode job = postJson("/api/v1/briefs/generate", "{\"portfolioId\": \"" + portfolio + "\", \"date\": \"2026-11-01\"}");
        UUID id = UUID.fromString(job.path("id").asString());
        assertThat(briefJobs.claim(5)).hasSize(1); // the worker "crashes" before processing
        jdbc.update("update brief_jobs set claimed_at = now() - interval '1 hour' where id = ?", id);

        assertThat(briefJobs.recoverExpired(Duration.ofMinutes(15))).isEqualTo(1);
        assertThat(getJson("/api/v1/briefs/jobs/" + id).path("errorCode").asString()).isEqualTo("INTERRUPTED");
        runAll();

        JsonNode done = getJson("/api/v1/briefs/jobs/" + id);
        assertThat(done.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.path("attempts").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from briefs")).isEqualTo(1);
    }

    @Test
    void withoutPortfoliosTheScheduledBriefIsGeneral() throws Exception {
        jdbc.update("delete from portfolios");
        assertThat(scheduler.enqueueDue()).isEqualTo(1);
        runAll();

        JsonNode list = getJson("/api/v1/briefs?from=2026-11-01&to=2026-11-01");
        assertThat(list.path("content")).hasSize(1);
        JsonNode item = list.path("content").get(0);
        assertThat(item.hasNonNull("portfolioId")).isFalse();
        JsonNode brief = getJson("/api/v1/briefs/" + item.path("id").asString());
        assertThat(sections(brief).get("PORTFOLIO_UPDATES").toString()).contains("Bản tin chung");
        assertThat(getJson("/api/v1/briefs?from=2026-11-02").path("totalElements").asInt()).isZero();
        mvc.perform(get("/api/v1/briefs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    /** Scripted model: cites the right evidence for FPT, invents S99, and suggests a trade that must be dropped. */
    private static String briefReply(Prompt prompt) {
        String user = prompt.getInstructions().getLast().getText();
        Map<String, String> sources = new LinkedHashMap<>();
        Matcher source = SOURCE.matcher(user);
        while (source.find()) {
            sources.put(source.group(1), source.group(2));
        }
        String profit = sources.entrySet().stream().filter(e -> e.getValue().contains("Lợi nhuận quý 3")).map(Map.Entry::getKey)
                .findFirst().orElse("S1");
        String risk = sources.entrySet().stream().filter(e -> e.getValue().contains("Rủi ro định giá")).map(Map.Entry::getKey)
                .findFirst().orElse("S2");
        Matcher holding = HOLDING.matcher(user);
        boolean fptPlanned = false;
        while (holding.find()) {
            fptPlanned |= holding.group(1).equals("FPT") && List.of(holding.group(2).split(",")).contains(profit);
        }
        return """
                {"executiveSummary": "Lợi nhuận quý 3 của FPT tăng 25%% [%1$s]. Có thêm thông tin quan trọng [S99].",
                 "portfolioFindings": [%3$s{"symbol": "MWG", "finding": "MWG mở rộng cửa hàng [%1$s]."}],
                 "risks": ["Rủi ro định giá cao sau giai đoạn tăng mạnh [%2$s]."],
                 "followUps": ["Nên mua thêm FPT ngay", "Đọc báo cáo định giá FPT"]}"""
                .formatted(profit, risk, fptPlanned
                        ? "{\"symbol\": \"FPT\", \"finding\": \"Lợi nhuận quý 3 của FPT tăng 25% nhờ mảng công nghệ [" + profit + "].\"}, "
                        : "");
    }

    /** Requests a brief for the portfolio and date through the API and runs the queue; returns the brief id. */
    private UUID generate(String date) throws Exception {
        JsonNode job = postJson("/api/v1/briefs/generate", "{\"portfolioId\": \"" + portfolio + "\", \"date\": \"" + date + "\"}");
        runAll();
        JsonNode done = getJson("/api/v1/briefs/jobs/" + job.path("id").asString());
        assertThat(done.path("status").asString()).isEqualTo("SUCCEEDED");
        return UUID.fromString(done.path("briefId").asString());
    }

    private void runAll() {
        for (int round = 0; round < 20; round++) {
            List<BriefJobQueue.ClaimedJob> claimed = briefJobs.claim(10);
            if (claimed.isEmpty() && count("select count(*) from brief_jobs where status in ('PENDING', 'RUNNING')") == 0) {
                return;
            }
            claimed.forEach(briefProcessor::process);
        }
        throw new AssertionError("Brief jobs did not finish");
    }

    private void availableAt(Instant indexedAt, Instant importedAt, UUID... documentIds) {
        for (UUID id : documentIds) {
            jdbc.update("update knowledge_sources set first_indexed_at = ? where document_id = ?", Timestamp.from(indexedAt), id);
            jdbc.update("update document set uploaded_at = ? where id = ?", Timestamp.from(importedAt), id);
        }
    }

    private UUID pdf(String filename, String brokerage, String ticker, ReportType type, String published, List<String> lines) {
        return documents.upload(new UploadDocumentCommand(new ByteArrayResource(TestResearchPdfs.textPdf(List.of(lines))), filename, brokerage,
                type, ticker, LocalDate.parse(published))).getId();
    }

    private UUID instrument(String symbol, String name) {
        return instrumentService.create(new InstrumentCommand(symbol, name, InstrumentType.STOCK, Exchange.HOSE, null, List.of())).id();
    }

    private void buy(String symbol, String quantity, String price) {
        LocalDate today = NOW.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        portfolioService.addTransaction(portfolio, new TransactionCommand(instruments.get(symbol), TransactionType.BUY, today.minusDays(30),
                new BigDecimal(quantity), new BigDecimal(price), BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    private static Map<String, JsonNode> sections(JsonNode brief) {
        Map<String, JsonNode> sections = new LinkedHashMap<>();
        brief.path("sections").forEach(section -> sections.put(section.path("key").asString(), section));
        return sections;
    }

    private static List<JsonNode> items(JsonNode section, String kind) {
        List<JsonNode> items = new ArrayList<>();
        section.path("items").forEach(item -> {
            if (item.path("kind").asString().equals(kind)) {
                items.add(item);
            }
        });
        return items;
    }

    private static JsonNode citation(JsonNode brief, UUID sourceId) {
        for (JsonNode citation : brief.path("citations")) {
            if (citation.path("sourceId").asString().equals(sourceId.toString())) {
                return citation;
            }
        }
        throw new AssertionError("no citation for " + sourceId + " in " + brief.path("citations"));
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        var request = post(path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-brief-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Test clock; the application uses it for "now" (brief periods, cutoffs, upload times). */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            now = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

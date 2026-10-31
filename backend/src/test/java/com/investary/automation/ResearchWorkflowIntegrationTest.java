package com.investary.automation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.investary.TestDatabase;
import com.investary.automation.application.workflow.WorkflowJobProcessor;
import com.investary.automation.application.workflow.WorkflowService;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.document.application.DocumentService;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.email.TestEmails;
import com.investary.email.application.EmailExceptions.DuplicateEmailException;
import com.investary.email.application.EmailImportService;
import com.investary.email.domain.SourceType;
import com.investary.knowledge.FakeAiConfiguration;
import com.investary.knowledge.FakeEmbeddingModel;
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
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.SourceLinkView;
import com.investary.research.download.DownloadJobProcessor;
import com.investary.research.download.DownloadJobQueue;
import com.investary.research.link.LinkStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 5 / Milestone 2 end to end: import → links → automatic (or manual) download from a WireMock
 * "brokerage" → indexing with the fake embedding model → deterministic portfolio matching → impact
 * assessment with the scripted chat model. No real website, Gmail account or AI provider is used.
 */
@SpringBootTest(properties = {
        "investary.download.allow-http=true",
        "investary.download.allow-private-networks=true",
        "investary.download.allow-any-port=true",
        "investary.download.additional-allowed-domains=localhost",
        "investary.download.max-attempts=1",
        "investary.workflow.max-auto-assessments-per-day=3"})
@AutoConfigureMockMvc
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class ResearchWorkflowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @RegisterExtension
    static WireMockExtension brokerage = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

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
    DocumentService documents;

    @Autowired
    ResearchQueryService research;

    @Autowired
    WorkflowService workflows;

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

    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    private WorkflowTestSupport support;
    private UUID portfolio;
    private final Map<String, UUID> instruments = new HashMap<>();
    private UUID account;
    private UUID syncRun;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from gmail_accounts");
        jdbc.update("delete from email_source_rules");
        jdbc.update("delete from portfolios");
        jdbc.update("delete from instruments");
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        jdbc.update("update workflow_settings set activated_at = now() - interval '1 day'");
        brokerage.resetAll();
        chatModel.reset();
        chatModel.reply("{\"overall\": \"Nhận xét: cần đọc thêm nguồn.\", \"items\": []}");
        embeddingModel.failNext(0);
        support = new WorkflowTestSupport(jobs, processor, downloadQueue, downloadProcessor, indexQueue, indexProcessor, jdbc);

        portfolio = portfolioService.create(new PortfolioCommand("Danh mục thử", null, null)).id();
        instruments.put("IMP", instrument("IMP", "Imexpharm", InstrumentType.STOCK));
        instruments.put("VNM", instrument("VNM", "Vinamilk", InstrumentType.STOCK));
        instruments.put("E1VFVN30", instrument("E1VFVN30", "Quỹ ETF VN30", InstrumentType.ETF));
        buy("IMP", "100");
        buy("VNM", "50");
        buy("E1VFVN30", "1000");

        account = UUID.randomUUID();
        jdbc.update("insert into gmail_accounts (id, email_address, status) values (?, 'investor@example.com', 'DISCONNECTED')", account);
        syncRun = UUID.randomUUID();
        jdbc.update("""
                insert into gmail_sync_runs (id, account_id, trigger_type, mode, status, started_at)
                values (?, ?, 'MANUAL', 'INITIAL', 'RUNNING', now())""", syncRun, account);
    }

    @Test
    void gmailEmailWithAutoDownloadIsProcessedEndToEndAndOnlyOnce() throws Exception {
        UUID rule = rule("TCBS", true);
        stubReport("/reports/IMP_Q3.pdf", "IMP");

        UUID email = gmailImport(newsletter("e2e", "/reports/IMP_Q3.pdf"), rule);
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(workflow.path("trigger").asString()).isEqualTo("GMAIL");
        assertThat(stage(workflow, "EMAIL_PARSED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "LINKS_CLASSIFIED").path("counts").path("pdfLinks").asInt()).isEqualTo(1);
        JsonNode documentsStage = stage(workflow, "DOCUMENTS_PROCESSED");
        assertThat(documentsStage.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(documentsStage.path("counts").path("downloaded").asInt()).isEqualTo(1);
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("counts").path("sources").asInt()).isEqualTo(2);
        assertThat(stage(workflow, "PORTFOLIO_MATCHED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "COMPLETED").path("status").asString()).isEqualTo("COMPLETED");

        // Deterministic match: the held ticker only; the ETF is never expanded into constituents.
        List<String> matched = new ArrayList<>();
        workflow.path("matches").forEach(m -> matched.add(m.path("symbol").asString()));
        assertThat(matched).containsExactly("IMP");

        // Promotion links are never fetched; the report is in the library and linked to the email.
        brokerage.verify(1, getRequestedFor(urlEqualTo("/reports/IMP_Q3.pdf")));
        brokerage.verify(0, getRequestedFor(urlEqualTo("/promo")));
        assertThat(count("select count(*) from message_documents where source_message_id = ? and relationship_type = 'LINKED_REPORT'", email))
                .isEqualTo(1);

        // One assessment, with the trigger, versions and evidence from stored chunks.
        Map<String, Object> assessment = jdbc.queryForMap(
                "select id, trigger_source_type, prompt_version, index_version, ai_status from impact_assessments where trigger_source_id = ?",
                email);
        assertThat(assessment.get("trigger_source_type")).isEqualTo("EMAIL");
        assertThat(assessment.get("prompt_version")).isEqualTo("i1");
        assertThat((String) assessment.get("index_version")).isNotBlank();
        assertThat(assessment.get("ai_status")).isEqualTo("COMPLETED");
        String evidence = jdbc.queryForObject("""
                select evidence::text from impact_assessment_items where assessment_id = ? and symbol = 'IMP'""", String.class,
                assessment.get("id"));
        assertThat(json.readTree(evidence).get(0).path("chunkId").asString()).isNotBlank();
        assertThat(chatModel.prompts()).hasSize(1);

        // Processing again changes nothing: no second download, no second assessment, no model call.
        mvc.perform(post("/api/v1/automation/workflows/{id}/process", email)).andExpect(status().isAccepted());
        support.runAll();
        brokerage.verify(1, getRequestedFor(urlEqualTo("/reports/IMP_Q3.pdf")));
        assertThat(count("select count(*) from impact_assessments where trigger_source_id = ?", email)).isEqualTo(1);
        assertThat(chatModel.prompts()).hasSize(1);
        assertThat(workflow(email).path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void uploadedEmlWaitsForManualDownloadWhichReopensIndexingAndMatching() throws Exception {
        stubReport("/reports/IMP_Q3.pdf", "IMP");
        UUID email = emailImport.importRaw(newsletter("manual", "/reports/IMP_Q3.pdf"), SourceType.EML_UPLOAD, "manual.eml");
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "DOCUMENTS_PROCESSED").path("status").asString()).isEqualTo("SKIPPED");
        assertThat(stage(workflow, "DOCUMENTS_PROCESSED").path("reason").asString()).isEqualTo("AUTO_DOWNLOAD_DISABLED");
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("counts").path("sources").asInt()).isEqualTo(1);
        brokerage.verify(0, getRequestedFor(urlEqualTo("/reports/IMP_Q3.pdf")));
        assertThat(count("select count(*) from impact_assessments where trigger_source_id = ?", email)).isEqualTo(1);

        SourceLinkView link = research.links(email).stream().filter(l -> l.status() == LinkStatus.READY).findFirst().orElseThrow();
        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", email, link.id())).andExpect(status().isAccepted());
        support.runAll();

        workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("counts").path("sources").asInt()).isEqualTo(2);
        // The content changed (a report was added), so a new assessment reflects it; the first is kept.
        assertThat(count("select count(*) from impact_assessments where trigger_source_id = ?", email)).isEqualTo(2);
    }

    @Test
    void emailWithoutLinksOrRelevantHoldingsCompletesWithoutCallingTheModel() throws Exception {
        UUID email = emailImport.importRaw(TestEmails.builder().subject("Bản tin vĩ mô").text("Lãi suất liên ngân hàng giảm nhẹ trong tuần.")
                .build(), SourceType.EML_UPLOAD, "macro.eml");
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "LINKS_CLASSIFIED").path("reason").asString()).isEqualTo("NO_LINKS");
        assertThat(stage(workflow, "DOCUMENTS_PROCESSED").path("reason").asString()).isEqualTo("NO_ELIGIBLE_LINKS");
        assertThat(stage(workflow, "PORTFOLIO_MATCHED").path("reason").asString()).isEqualTo("NO_RELEVANT_HOLDINGS");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("status").asString()).isEqualTo("SKIPPED");
        assertThat(chatModel.prompts()).isEmpty();
        assertThat(count("select count(*) from impact_assessments where trigger_source_id = ?", email)).isZero();
    }

    @Test
    void duplicateImportDoesNotStartASecondWorkflow() {
        byte[] raw = newsletter("dup", "/reports/IMP_Q3.pdf");
        UUID email = emailImport.importRaw(raw, SourceType.EML_UPLOAD, "a.eml");

        assertThatThrownBy(() -> emailImport.importRaw(raw, SourceType.GMAIL, "b.eml")).isInstanceOf(DuplicateEmailException.class);
        assertThat(count("select count(*) from research_workflows where source_message_id = ?", email)).isEqualTo(1);
        assertThat(count("select count(*) from research_workflows", null)).isEqualTo(1);
    }

    @Test
    void failedReportMakesTheWorkflowPartialWhileOtherReportsAreProcessed() throws Exception {
        UUID rule = rule("TCBS", true);
        stubReport("/reports/IMP_Q3.pdf", "IMP");
        brokerage.stubFor(WireMock.get(urlEqualTo("/reports/HPG_Q3.pdf")).willReturn(aResponse().withStatus(404)));
        byte[] raw = TestEmails.builder().subject("Hai báo cáo").html("""
                <p><strong>1. Latest Research Publication</strong></p>
                <p><a href="%s"><strong>IMP - Q3 Earnings Update</strong></a></p>
                <p><a href="%s"><strong>HPG - Q3 Earnings Update</strong></a></p>""".formatted(url("/reports/IMP_Q3.pdf"),
                url("/reports/HPG_Q3.pdf"))).build();

        UUID email = gmailImport(raw, rule);
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("PARTIAL");
        JsonNode documentsStage = stage(workflow, "DOCUMENTS_PROCESSED");
        assertThat(documentsStage.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(documentsStage.path("counts").path("downloaded").asInt()).isEqualTo(1);
        assertThat(documentsStage.path("counts").path("failed").asInt()).isEqualTo(1);
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void autoDownloadDisabledOnTheRuleKeepsLinksForManualDownload() throws Exception {
        UUID rule = rule("TCBS", false);
        stubReport("/reports/IMP_Q3.pdf", "IMP");

        UUID email = gmailImport(newsletter("rule-off", "/reports/IMP_Q3.pdf"), rule);
        support.runAll();

        assertThat(stage(workflow(email), "DOCUMENTS_PROCESSED").path("reason").asString()).isEqualTo("AUTO_DOWNLOAD_DISABLED");
        assertThat(research.links(email)).anySatisfy(l -> assertThat(l.status()).isEqualTo(LinkStatus.READY));
        brokerage.verify(0, getRequestedFor(urlEqualTo("/reports/IMP_Q3.pdf")));
    }

    @Test
    void samePdfReferencedByTwoEmailsIsStoredOnceAndLinkedToBoth() throws Exception {
        UUID rule = rule("TCBS", true);
        stubReport("/reports/IMP_Q3.pdf", "IMP");

        UUID first = gmailImport(newsletter("first", "/reports/IMP_Q3.pdf"), rule);
        UUID second = gmailImport(newsletter("second", "/reports/IMP_Q3.pdf"), rule);
        support.runAll();

        assertThat(count("select count(*) from document", null)).isEqualTo(1);
        assertThat(count("select count(*) from message_documents where source_message_id in (?, ?)", first, second)).isEqualTo(2);
        assertThat(workflow(first).path("status").asString()).isEqualTo("COMPLETED");
        assertThat(workflow(second).path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void failedIndexingFailsTheWorkflowAndARetryRecoversWithoutReimporting() throws Exception {
        embeddingModel.failNext(10_000);
        UUID email = emailImport.importRaw(TestEmails.builder().subject("IMP cập nhật").text("Cổ phiếu IMP tăng trưởng doanh thu quý 3.")
                .build(), SourceType.EML_UPLOAD, "imp.eml");
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("FAILED");
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("status").asString()).isEqualTo("FAILED");
        assertThat(stage(workflow, "PORTFOLIO_MATCHED").path("reason").asString()).isEqualTo("NOT_REACHED");
        JsonNode failed = job(workflow, "INDEX");
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("INDEXING_FAILED");

        mvc.perform(get("/api/v1/automation/jobs").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(failed.path("id").asString()));

        embeddingModel.failNext(0);
        mvc.perform(post("/api/v1/automation/jobs/{id}/retry", failed.path("id").asString())).andExpect(status().isAccepted());
        support.runAll();

        workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from source_messages", null)).isEqualTo(1);
        mvc.perform(post("/api/v1/automation/jobs/{id}/retry", failed.path("id").asString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_NOT_RETRYABLE"));
    }

    @Test
    void failedModelCallMakesTheWorkflowPartialAndARetryCompletesIt() throws Exception {
        chatModel.reply(prompt -> {
            throw new IllegalStateException("Simulated provider outage");
        });
        UUID email = emailImport.importRaw(TestEmails.builder().subject("IMP cập nhật").text("Cổ phiếu IMP tăng trưởng doanh thu quý 3.")
                .build(), SourceType.EML_UPLOAD, "imp.eml");
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("status").asString()).isEqualTo("FAILED");
        JsonNode assess = job(workflow, "ASSESS");
        assertThat(assess.path("attempts").asInt()).isEqualTo(3);
        assertThat(assess.path("errorCode").asString()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(assess.path("errorSummary").asString()).doesNotContain("Exception:").doesNotContain("http");
        assertThat(count("select count(*) from impact_assessments", null)).isZero();

        chatModel.reply("{\"overall\": \"Nhận xét: cần đọc thêm nguồn.\", \"items\": []}");
        mvc.perform(post("/api/v1/automation/workflows/{id}/process", email)).andExpect(status().isAccepted());
        support.runAll();

        assertThat(workflow(email).path("status").asString()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from impact_assessments where trigger_source_id = ?", email)).isEqualTo(1);
    }

    @Test
    void dailyLimitSkipsNewAutomaticAssessments() throws Exception {
        UUID earlier = emailImport.importRaw(TestEmails.builder().subject("Cũ").text("Ghi chú.").build(), SourceType.EML_UPLOAD, "old.eml");
        support.runAll();
        UUID workflowId = UUID.fromString(workflow(earlier).path("id").asString());
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    insert into automation_jobs (id, job_type, workflow_id, portfolio_id, idempotency_key, status, outcome, attempts,
                                                 max_attempts, next_attempt_at, completed_at, processing_version)
                    values (?, 'ASSESS', ?, ?, ?, 'SUCCEEDED', 'CREATED', 1, 3, now(), now(), 'w1')""",
                    UUID.randomUUID(), workflowId, portfolio, "limit-" + i);
        }

        UUID email = emailImport.importRaw(TestEmails.builder().subject("IMP cập nhật").text("Cổ phiếu IMP tăng trưởng doanh thu quý 3.")
                .build(), SourceType.EML_UPLOAD, "imp.eml");
        support.runAll();

        JsonNode workflow = workflow(email);
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("status").asString()).isEqualTo("SKIPPED");
        assertThat(stage(workflow, "IMPACT_ASSESSED").path("reason").asString()).isEqualTo("DAILY_LIMIT_REACHED");
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void jobsInterruptedByARestartAreRecoveredAndAStaleWorkerCannotWrite() throws Exception {
        UUID email = emailImport.importRaw(TestEmails.builder().subject("Ghi chú").text("Không có mã cổ phiếu.").build(),
                SourceType.EML_UPLOAD, "note.eml");
        List<AutomationJobQueue.ClaimedJob> claimed = jobs.claim(10);
        assertThat(claimed).hasSize(1);
        // The application "stops" with the job RUNNING; its lease expires.
        jdbc.update("update automation_jobs set claimed_at = now() - interval '1 hour' where id = ?", claimed.getFirst().id());
        assertThat(jobs.recoverExpired(Duration.ofMinutes(15))).isEqualTo(1);

        support.runAll();
        assertThat(workflow(email).path("status").asString()).isEqualTo("COMPLETED");

        // The old worker finishes late: its claim token no longer matches, so nothing changes.
        processor.process(claimed.getFirst());
        assertThat(jobs.find(claimed.getFirst().id()).orElseThrow().attempts()).isEqualTo(2);
        assertThat(workflow(email).path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void deletingTheEmailMidWorkflowRemovesItsWorkflowAndLeavesLibraryPdfs() throws Exception {
        UUID rule = rule("TCBS", true);
        stubReport("/reports/IMP_Q3.pdf", "IMP");
        UUID email = gmailImport(newsletter("delete", "/reports/IMP_Q3.pdf"), rule);
        support.runDueJobsOnce();
        support.runDueJobsOnce();
        downloadQueue.claim(10).forEach(downloadProcessor::process);
        List<AutomationJobQueue.ClaimedJob> inFlight = jobs.claim(10);

        mvc.perform(delete("/api/v1/emails/{id}", email)).andExpect(status().isNoContent());
        inFlight.forEach(processor::process);

        assertThat(count("select count(*) from research_workflows", null)).isZero();
        assertThat(count("select count(*) from automation_jobs", null)).isZero();
        assertThat(count("select count(*) from document", null)).isEqualTo(1);
        mvc.perform(get("/api/v1/automation/workflows/{id}", email))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_NOT_FOUND"));
    }

    @Test
    void sourcesImportedBeforeTheFeatureAreOnlyProcessedOnRequest() throws Exception {
        UUID email = emailImport.importRaw(TestEmails.builder().subject("IMP cập nhật").text("Cổ phiếu IMP tăng trưởng doanh thu quý 3.")
                .build(), SourceType.EML_UPLOAD, "old.eml");
        jdbc.update("delete from research_workflows");
        jdbc.update("update source_messages set imported_at = now() - interval '2 days'");

        mvc.perform(get("/api/v1/automation/workflows/{id}", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.importedBeforeAutomation").value(true));
        assertThat(workflows.reconcile()).isZero();

        mvc.perform(post("/api/v1/automation/workflows/{id}/process", email))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trigger").value("MANUAL"));
        support.runAll();
        assertThat(workflow(email).path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void missedImportEventIsPickedUpByTheReconcile() throws Exception {
        UUID email = emailImport.importRaw(TestEmails.builder().subject("Ghi chú").text("Ghi chú.").build(), SourceType.EML_UPLOAD, "a.eml");
        jdbc.update("delete from research_workflows");
        jdbc.update("update source_messages set imported_at = now() - interval '10 minutes'");

        assertThat(workflows.reconcile()).isEqualTo(1);
        support.runAll();

        assertThat(workflow(email).path("trigger").asString()).isEqualTo("RECONCILE");
        assertThat(workflow(email).path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void libraryUploadRunsADocumentWorkflow() throws Exception {
        UUID document = documents.upload(new UploadDocumentCommand(new ByteArrayResource(reportPdf("IMP")), "imp.pdf", "TCBS", null,
                "IMP", today)).getId();
        support.runAll();

        JsonNode workflow = workflow(document);
        assertThat(workflow.path("sourceType").asString()).isEqualTo("DOCUMENT");
        assertThat(workflow.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage(workflow, "EMAIL_PARSED").path("reason").asString()).isEqualTo("NOT_APPLICABLE");
        assertThat(stage(workflow, "KNOWLEDGE_INDEXED").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(workflow.path("matches").get(0).path("symbol").asString()).isEqualTo("IMP");
        assertThat(count("select count(*) from impact_assessments where trigger_source_id = ? and trigger_source_type = 'DOCUMENT'", document))
                .isEqualTo(1);
    }

    private UUID gmailImport(byte[] raw, UUID rule) {
        UUID email = emailImport.importRaw(raw, SourceType.GMAIL, "gmail.eml");
        // Gmail sync records the message mapping right after the import, as GmailSyncRunner does.
        jdbc.update("""
                insert into gmail_messages (id, account_id, gmail_message_id, status, source_rule_id, source_message_id, sync_run_id)
                values (?, ?, ?, 'IMPORTED', ?, ?, ?)""", UUID.randomUUID(), account, "m-" + UUID.randomUUID(), rule, email, syncRun);
        return email;
    }

    private UUID rule(String name, boolean autoDownload) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into email_source_rules (id, name, sender_domains, auto_pdf_download) values (?, ?, '{tcbs.com.vn}', ?)""",
                id, name, autoDownload);
        return id;
    }

    private void stubReport(String path, String ticker) {
        brokerage.stubFor(WireMock.get(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", "application/pdf")
                .withBody(reportPdf(ticker))));
    }

    private static byte[] reportPdf(String ticker) {
        return TestResearchPdfs.textPdf(List.of(List.of(ticker + " - Cập nhật kết quả kinh doanh quý 3",
                "Doanh thu của " + ticker + " tăng 12% so với cùng kỳ nhờ kênh bán lẻ.")));
    }

    private byte[] newsletter(String marker, String reportPath) {
        return TestEmails.builder().subject("Cơ hội đầu tư | " + marker).html("""
                <p><strong>1. Latest Research Publication</strong></p>
                <p><a href="%s"><strong>IMP - Q3 Earnings Update</strong></a></p>
                <p><strong>4. Promotions</strong></p>
                <p><a href="%s">Margin offer</a></p>
                <!-- %s -->""".formatted(url(reportPath), url("/promo"), marker)).build();
    }

    private static String url(String path) {
        return "http://localhost:" + brokerage.getPort() + path;
    }

    private JsonNode workflow(UUID sourceId) throws Exception {
        String body = mvc.perform(get("/api/v1/automation/workflows/{id}", sourceId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
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

    private UUID instrument(String symbol, String name, InstrumentType type) {
        return instrumentService.create(new InstrumentCommand(symbol, name, type, Exchange.HOSE, null, List.of())).id();
    }

    private void buy(String symbol, String quantity) {
        portfolioService.addTransaction(portfolio, new TransactionCommand(instruments.get(symbol), TransactionType.BUY, today.minusDays(30),
                new BigDecimal(quantity), new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    private int count(String sql, Object... args) {
        Integer value = args == null ? jdbc.queryForObject(sql, Integer.class) : jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-workflow-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

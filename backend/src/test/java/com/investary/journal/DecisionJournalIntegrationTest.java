package com.investary.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;
import com.investary.common.error.ApiException;
import com.investary.document.TestPdfs;
import com.investary.document.application.DocumentService;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.document.domain.ReportType;
import com.investary.email.application.EmailImportService;
import com.investary.investor.application.GoalCommand;
import com.investary.investor.application.InvestorProfileService;
import com.investary.investor.application.ProfileCommand;
import com.investary.investor.domain.ExperienceLevel;
import com.investary.investor.domain.GoalPriority;
import com.investary.investor.domain.InvestmentHorizon;
import com.investary.investor.domain.RiskTolerance;
import com.investary.journal.application.DecisionCommands.DecisionCommand;
import com.investary.journal.application.DecisionService;
import com.investary.journal.domain.DecisionType;
import com.investary.knowledge.FakeAiConfiguration;
import com.investary.knowledge.KnowledgeTestSupport;
import com.investary.knowledge.ScriptedChatModel;
import com.investary.knowledge.SyntheticResearchCorpus;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.portfolio.application.InstrumentService;
import com.investary.portfolio.application.PortfolioCommands.InstrumentCommand;
import com.investary.portfolio.application.PortfolioCommands.PortfolioCommand;
import com.investary.portfolio.application.PortfolioCommands.PriceCommand;
import com.investary.portfolio.application.PortfolioCommands.TransactionCommand;
import com.investary.portfolio.application.PortfolioService;
import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;
import com.investary.portfolio.domain.TransactionType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The decision journal end to end on PostgreSQL: lifecycle, immutable snapshot, audit trail, evidence from every
 * source kind, transaction links and optimistic locking. All data is synthetic; the models are fakes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class DecisionJournalIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    static final Path STORAGE = createTempDir();

    private static boolean corpusLoaded;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("investary.storage.root", () -> STORAGE.resolve("documents").toString());
        registry.add("investary.email.storage.root", () -> STORAGE.resolve("emails").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DocumentService documents;

    @Autowired
    EmailImportService emailImport;

    @Autowired
    KnowledgeIndexQueue queue;

    @Autowired
    KnowledgeIndexProcessor processor;

    @Autowired
    ScriptedChatModel chatModel;

    @Autowired
    PortfolioService portfolioService;

    @Autowired
    InstrumentService instrumentService;

    @Autowired
    InvestorProfileService profileService;

    @Autowired
    DecisionService decisionService;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    private UUID portfolio;
    private UUID other;
    private UUID fpt;
    private UUID vnm;
    private UUID hpg;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from investment_decisions");
        jdbc.update("delete from portfolios");
        jdbc.update("delete from instruments");
        jdbc.update("delete from investor_goals");
        jdbc.update("delete from investor_profile");
        if (!corpusLoaded) {
            new SyntheticResearchCorpus(documents, emailImport).load();
            KnowledgeTestSupport.indexAll(queue, processor);
            corpusLoaded = true;
        }
        chatModel.reset();
        portfolio = portfolioService.create(new PortfolioCommand("Danh mục nhật ký", null, null)).id();
        other = portfolioService.create(new PortfolioCommand("Danh mục khác", null, null)).id();
        fpt = instrument("FPT");
        vnm = instrument("VNM");
        hpg = instrument("HPG");
        trade(portfolio, fpt, TransactionType.BUY, "100", "100000");
        trade(portfolio, vnm, TransactionType.BUY, "50", "60000");
        instrumentService.addPrice(fpt, new PriceCommand(new BigDecimal("110000"), today.minusDays(1), "Synthetic board", null));
    }

    @Test
    void decisionLifecycleKeepsAnImmutableSnapshotAndAFullAuditTrail() throws Exception {
        profileService.saveProfile(new ProfileCommand(ExperienceLevel.INTERMEDIATE, RiskTolerance.MODERATE, "Tăng trưởng dài hạn",
                InvestmentHorizon.LONG, List.of(), null, null, null));
        profileService.createGoal(new GoalCommand("Quỹ hưu trí", null, new BigDecimal("2000000000"), today.plusYears(10), "VND",
                GoalPriority.HIGH, null));

        JsonNode created = send(post("/api/v1/decisions"), body(decision("BUY", fpt, portfolio, null)), 201);
        String id = created.path("id").asString();
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("symbol").asString()).isEqualTo("FPT");
        assertThat(created.path("portfolioName").asString()).isEqualTo("Danh mục nhật ký");
        assertThat(created.path("snapshot").isNull()).isTrue();
        assertThat(texts(created.path("actions"))).contains("EDIT", "FINALIZE", "CANCEL", "LINK_TRANSACTION");

        Map<String, Object> edit = decision("BUY", fpt, portfolio, created.path("version").asInt());
        edit.put("title", "Mua thêm FPT (sửa)");
        edit.put("intendedQuantity", "20");
        JsonNode edited = send(put("/api/v1/decisions/" + id), body(edit), 200);
        assertThat(edited.path("title").asString()).isEqualTo("Mua thêm FPT (sửa)");
        assertThat(edited.path("version").asInt()).isGreaterThan(created.path("version").asInt());

        JsonNode decided = send(post("/api/v1/decisions/" + id + "/finalize"), transition(edited, null), 200);
        assertThat(decided.path("status").asString()).isEqualTo("DECIDED");
        JsonNode snapshot = decided.path("snapshot");
        assertThat(snapshot.path("portfolio").path("name").asString()).isEqualTo("Danh mục nhật ký");
        assertThat(snapshot.path("portfolio").path("holdings").size()).isEqualTo(2);
        assertThat(snapshot.path("position").path("held").asBoolean()).isTrue();
        assertThat(new BigDecimal(snapshot.path("position").path("quantity").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(snapshot.path("position").path("latestPrice").asString())).isEqualByComparingTo("110000");
        assertThat(texts(snapshot.path("missingPrices"))).containsExactly("VNM");
        JsonNode vnmHolding = holding(snapshot.path("portfolio").path("holdings"), "VNM");
        assertThat(vnmHolding.path("marketValue").isNull()).isTrue();
        assertThat(vnmHolding.path("latestPrice").isNull()).isTrue();
        assertThat(snapshot.path("portfolio").path("totalMarketValue").isNull()).isTrue();
        assertThat(snapshot.path("investor").path("riskTolerance").asString()).isEqualTo("MODERATE");
        assertThat(snapshot.path("investor").path("goals").get(0).path("name").asString()).isEqualTo("Quỹ hưu trí");

        // Later trades and prices never change the stored snapshot.
        UUID first = trade(portfolio, fpt, TransactionType.BUY, "20", "105000");
        UUID second = trade(portfolio, fpt, TransactionType.BUY, "5", "104000");
        instrumentService.addPrice(vnm, new PriceCommand(new BigDecimal("65000"), today, "Synthetic board", null));
        JsonNode reloaded = send(get("/api/v1/decisions/" + id), null, 200);
        assertThat(reloaded.path("snapshot")).isEqualTo(snapshot);
        assertThatThrownBy(() -> jdbc.update("update decision_portfolio_snapshots set snapshot = '{}'::jsonb"))
                .hasMessageContaining("append-only");

        // Amendments: narrative fields with a reason; locked fields never.
        Map<String, Object> amend = decision("BUY", fpt, portfolio, reloaded.path("version").asInt());
        amend.put("title", "Mua thêm FPT (sửa)");
        amend.put("intendedQuantity", "20");
        amend.put("rationale", "Luận điểm đã cập nhật");
        error(put("/api/v1/decisions/" + id), body(amend), 400, "VALIDATION_FAILED");
        amend.put("amendmentReason", "Thêm chi tiết");
        JsonNode amended = send(put("/api/v1/decisions/" + id), body(amend), 200);
        assertThat(amended.path("rationale").asString()).isEqualTo("Luận điểm đã cập nhật");
        Map<String, Object> locked = new LinkedHashMap<>(amend);
        locked.put("expectedVersion", amended.path("version").asInt());
        locked.put("decisionType", "SELL");
        locked.put("intendedQuantity", "30");
        JsonNode lockedError = error(put("/api/v1/decisions/" + id), body(locked), 409, "DECISION_FIELD_LOCKED");
        assertThat(texts(lockedError.path("fields"))).containsExactlyInAnyOrder("decisionType", "intendedQuantity");

        // A BUY is executed only through linked transactions.
        error(post("/api/v1/decisions/" + id + "/execute"), transition(amended, null), 409, "INVALID_DECISION_TRANSITION");
        JsonNode linked = send(post("/api/v1/decisions/" + id + "/transactions"), link(amended, first), 201);
        linked = send(post("/api/v1/decisions/" + id + "/transactions"), link(linked, second), 201);
        assertThat(linked.path("transactions").size()).isEqualTo(2);
        assertThat(linked.path("transactions").get(0).path("state").asString()).isEqualTo("CURRENT");
        JsonNode executed = send(post("/api/v1/decisions/" + id + "/execute"), transition(linked, null), 200);
        assertThat(executed.path("status").asString()).isEqualTo("EXECUTED");
        assertThat(executed.path("executedAt").isNull()).isFalse();
        error(post("/api/v1/decisions/" + id + "/cancel"), transition(executed, "Đổi ý"), 409, "INVALID_DECISION_TRANSITION");

        JsonNode history = send(get("/api/v1/decisions/" + id + "/history"), null, 200);
        List<String> kinds = new ArrayList<>();
        history.forEach(r -> kinds.add(r.path("kind").asString()));
        assertThat(kinds).containsExactly("CREATED", "EDITED", "FINALIZED", "AMENDED", "TRANSACTION_LINKED", "TRANSACTION_LINKED",
                "EXECUTED");
        JsonNode editRevision = history.get(1).path("changes");
        assertThat(editRevision.path("title").path("from").asString()).isEqualTo("Mua thêm FPT");
        assertThat(editRevision.path("title").path("to").asString()).isEqualTo("Mua thêm FPT (sửa)");
        assertThat(history.get(3).path("reason").asString()).isEqualTo("Thêm chi tiết");
        assertThat(history.get(3).path("changes").has("rationale")).isTrue();
        assertThatThrownBy(() -> jdbc.update("update decision_revisions set reason = 'x'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from decision_revisions")).hasMessageContaining("append-only");

        // The journal never wrote a transaction itself.
        assertThat(jdbc.queryForObject("select count(*) from portfolio_transactions where portfolio_id = ?", Integer.class, portfolio))
                .isEqualTo(4);
    }

    @Test
    void holdAndObserveCompleteWithoutTransactionsAndWatchlistSnapshotsHaveNoPortfolio() throws Exception {
        JsonNode hold = send(post("/api/v1/decisions"), body(decision("HOLD", vnm, portfolio, null)), 201);
        hold = send(post("/api/v1/decisions/" + id(hold) + "/finalize"), transition(hold, null), 200);
        hold = send(post("/api/v1/decisions/" + id(hold) + "/execute"), transition(hold, "Giữ nguyên"), 200);
        assertThat(hold.path("status").asString()).isEqualTo("EXECUTED");
        assertThat(hold.path("transactions").isEmpty()).isTrue();
        assertThat(hold.path("snapshot").path("position").path("latestPrice").isNull()).isTrue();
        assertThat(texts(hold.path("snapshot").path("limitations"))).anySatisfy(l -> assertThat(l).contains("VNM"));

        JsonNode observe = send(post("/api/v1/decisions"), body(decision("OBSERVE", hpg, null, null)), 201);
        assertThat(texts(observe.path("actions"))).doesNotContain("LINK_TRANSACTION");
        observe = send(post("/api/v1/decisions/" + id(observe) + "/finalize"), transition(observe, null), 200);
        assertThat(observe.path("snapshot").path("portfolio").isNull()).isTrue();
        assertThat(observe.path("snapshot").path("position").path("held").asBoolean()).isFalse();
        assertThat(observe.path("snapshot").path("investor").path("configured").asBoolean()).isFalse();
        error(post("/api/v1/decisions/" + id(observe) + "/transactions"), link(observe, UUID.randomUUID()), 422, "TRANSACTION_LINK_INVALID");
        observe = send(post("/api/v1/decisions/" + id(observe) + "/execute"), transition(observe, null), 200);
        assertThat(observe.path("status").asString()).isEqualTo("EXECUTED");
    }

    @Test
    void invalidTransitionsAndIncompleteDecisionsAreRejected() throws Exception {
        Map<String, Object> noRationale = decision("SELL", fpt, portfolio, null);
        noRationale.remove("rationale");
        JsonNode draft = send(post("/api/v1/decisions"), body(noRationale), 201);
        error(post("/api/v1/decisions/" + id(draft) + "/finalize"), transition(draft, null), 400, "VALIDATION_FAILED");
        error(post("/api/v1/decisions/" + id(draft) + "/execute"), transition(draft, null), 409, "INVALID_DECISION_TRANSITION");
        error(post("/api/v1/decisions/" + id(draft) + "/cancel"), transition(draft, " "), 400, "VALIDATION_FAILED");

        Map<String, Object> noInstrument = decision("BUY", null, portfolio, null);
        error(post("/api/v1/decisions"), body(noInstrument), 400, "VALIDATION_FAILED");
        error(post("/api/v1/decisions"), body(decision("REBALANCE", null, null, null)), 400, "VALIDATION_FAILED");
        error(post("/api/v1/decisions"), body(decision("BUY", UUID.randomUUID(), portfolio, null)), 404, "INSTRUMENT_NOT_FOUND");
        error(get("/api/v1/decisions/" + UUID.randomUUID()), null, 404, "DECISION_NOT_FOUND");

        JsonNode cancelled = send(post("/api/v1/decisions/" + id(draft) + "/cancel"), transition(draft, "Không còn phù hợp"), 200);
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelReason").asString()).isEqualTo("Không còn phù hợp");
        assertThat(cancelled.path("actions").isEmpty()).isTrue();
        error(post("/api/v1/decisions/" + id(draft) + "/finalize"), transition(cancelled, null), 409, "INVALID_DECISION_TRANSITION");
        error(put("/api/v1/decisions/" + id(draft)), body(decision("SELL", fpt, portfolio, cancelled.path("version").asInt())), 409,
                "INVALID_DECISION_TRANSITION");
        error(post("/api/v1/decisions/" + id(draft) + "/evidence"), evidence(cancelled, "DOCUMENT", UUID.randomUUID()), 409,
                "INVALID_DECISION_TRANSITION");
        JsonNode history = send(get("/api/v1/decisions/" + id(draft) + "/history"), null, 200);
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(1).path("kind").asString()).isEqualTo("CANCELLED");

        JsonNode finalized = send(post("/api/v1/decisions"), body(decision("BUY", fpt, portfolio, null)), 201);
        finalized = send(post("/api/v1/decisions/" + id(finalized) + "/finalize"), transition(finalized, null), 200);
        error(post("/api/v1/decisions/" + id(finalized) + "/finalize"), transition(finalized, null), 409, "INVALID_DECISION_TRANSITION");
    }

    @Test
    void evidenceFromEverySourceIsCopiedValidatedAndShownUnavailableAfterDeletion() throws Exception {
        UUID document = documents.upload(new UploadDocumentCommand(new ByteArrayResource(TestPdfs.pdf("journal-evidence")),
                "fpt-tong-hop.pdf", "SSI", ReportType.STOCK_ANALYSIS, "FPT", today.minusDays(3))).getId();
        UUID email = jdbc.queryForObject("select id from source_messages order by imported_at limit 1", UUID.class);
        UUID researchItem = UUID.randomUUID();
        jdbc.update("""
                insert into research_items (id, source_message_id, item_type, ticker, brokerage, title, content, extraction_method,
                                            source_location)
                values (?, ?, 'STOCK_RECOMMENDATION', 'FPT', 'SSI', 'FPT: khuyến nghị tổng hợp', 'Nội dung tổng hợp về FPT.',
                        'TEST', 'body#1')""", researchItem, email);
        UUID chunk = jdbc.queryForObject("select id from knowledge_chunks order by created_at, chunk_index limit 1", UUID.class);
        chatModel.reply("không phải JSON");
        JsonNode assessment = json.readTree(mvc.perform(post("/api/v1/intelligence/portfolios/" + portfolio + "/impact-assessments"))
                .andReturn().getResponse().getContentAsString());
        UUID assessmentId = UUID.fromString(assessment.path("id").asString());

        JsonNode decision = send(post("/api/v1/decisions"), body(decision("BUY", fpt, portfolio, null)), 201);
        String id = id(decision);
        decision = send(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, "DOCUMENT", document), 201);
        decision = send(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, "EMAIL", email), 201);
        decision = send(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, "RESEARCH_ITEM", researchItem), 201);
        decision = send(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, "CITATION", chunk), 201);
        decision = send(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, "IMPACT_ASSESSMENT", assessmentId), 201);
        JsonNode items = decision.path("evidence");
        assertThat(items.size()).isEqualTo(5);
        items.forEach(item -> assertThat(item.path("available").asBoolean()).isTrue());
        assertThat(items.get(0).path("title").asString()).isEqualTo("fpt-tong-hop.pdf");
        assertThat(items.get(0).path("publisher").asString()).isEqualTo("SSI");
        assertThat(items.get(2).path("excerpt").asString()).isEqualTo("Nội dung tổng hợp về FPT.");
        assertThat(items.get(2).path("location").asString()).isEqualTo("body#1");
        String chunkText = jdbc.queryForObject("select content from knowledge_chunks where id = ?", String.class, chunk);
        assertThat(chunkText.strip()).startsWith(items.get(3).path("excerpt").asString().replace("…", ""));

        error(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, "DOCUMENT", document), 409, "DUPLICATE_EVIDENCE");
        for (String kind : List.of("DOCUMENT", "EMAIL", "RESEARCH_ITEM", "CITATION", "IMPACT_ASSESSMENT")) {
            error(post("/api/v1/decisions/" + id + "/evidence"), evidence(decision, kind, UUID.randomUUID()), 404,
                    "EVIDENCE_SOURCE_NOT_FOUND");
        }

        // Removal is audited and keeps the row.
        String researchEvidence = items.get(2).path("id").asString();
        error(delete("/api/v1/decisions/" + id + "/evidence/" + researchEvidence).param("expectedVersion", version(decision))
                .param("reason", " "), null, 400, "VALIDATION_FAILED");
        decision = send(delete("/api/v1/decisions/" + id + "/evidence/" + researchEvidence).param("expectedVersion", version(decision))
                .param("reason", "Trùng với báo cáo"), null, 200);
        assertThat(decision.path("evidence").get(2).path("removedReason").asString()).isEqualTo("Trùng với báo cáo");

        // Deleted sources stay visible as unavailable copies; nothing is substituted.
        documents.delete(document);
        jdbc.update("delete from impact_assessments where id = ?", assessmentId);
        JsonNode after = send(get("/api/v1/decisions/" + id), null, 200);
        JsonNode documentEvidence = after.path("evidence").get(0);
        assertThat(documentEvidence.path("available").asBoolean()).isFalse();
        assertThat(documentEvidence.path("title").asString()).isEqualTo("fpt-tong-hop.pdf");
        assertThat(documentEvidence.path("sourceId").asString()).isEqualTo(document.toString());
        assertThat(after.path("evidence").get(4).path("available").asBoolean()).isFalse();
        assertThat(after.path("evidence").get(1).path("available").asBoolean()).isTrue();

        JsonNode history = send(get("/api/v1/decisions/" + id + "/history"), null, 200);
        assertThat(history.get(history.size() - 1).path("kind").asString()).isEqualTo("EVIDENCE_REMOVED");
    }

    @Test
    void transactionLinksAreValidatedAuditedAndTrackChanges() throws Exception {
        UUID buy = trade(portfolio, fpt, TransactionType.BUY, "10", "101000");
        UUID sell = trade(portfolio, fpt, TransactionType.SELL, "5", "112000");
        UUID otherPortfolio = trade(other, fpt, TransactionType.BUY, "10", "101000");
        UUID vnmBuy = trade(portfolio, vnm, TransactionType.BUY, "10", "61000");

        JsonNode decision = send(post("/api/v1/decisions"), body(decision("BUY", fpt, portfolio, null)), 201);
        String id = id(decision);
        error(post("/api/v1/decisions/" + id + "/transactions"), link(decision, otherPortfolio), 422, "TRANSACTION_LINK_INVALID");
        error(post("/api/v1/decisions/" + id + "/transactions"), link(decision, vnmBuy), 422, "TRANSACTION_LINK_INVALID");
        error(post("/api/v1/decisions/" + id + "/transactions"), link(decision, sell), 422, "TRANSACTION_LINK_INVALID");
        error(post("/api/v1/decisions/" + id + "/transactions"), link(decision, UUID.randomUUID()), 404, "TRANSACTION_NOT_FOUND");
        decision = send(post("/api/v1/decisions/" + id + "/transactions"), link(decision, buy), 201);
        error(post("/api/v1/decisions/" + id + "/transactions"), link(decision, buy), 409, "DUPLICATE_TRANSACTION_LINK");

        JsonNode rebalance = send(post("/api/v1/decisions"), body(decision("REBALANCE", null, portfolio, null)), 201);
        rebalance = send(post("/api/v1/decisions/" + id(rebalance) + "/transactions"), link(rebalance, sell), 201);
        rebalance = send(post("/api/v1/decisions/" + id(rebalance) + "/transactions"), link(rebalance, vnmBuy), 201);
        assertThat(rebalance.path("transactions").size()).isEqualTo(2);

        // Editing or deleting the transaction is shown, with the linked facts kept.
        portfolioService.updateTransaction(portfolio, buy, new TransactionCommand(fpt, TransactionType.BUY, today.minusDays(2),
                new BigDecimal("12"), new BigDecimal("101000"), BigDecimal.ZERO, BigDecimal.ZERO, null));
        assertThat(send(get("/api/v1/decisions/" + id), null, 200).path("transactions").get(0).path("state").asString()).isEqualTo("CHANGED");
        portfolioService.deleteTransaction(portfolio, buy);
        JsonNode link = send(get("/api/v1/decisions/" + id), null, 200).path("transactions").get(0);
        assertThat(link.path("state").asString()).isEqualTo("DELETED");
        assertThat(link.path("transactionId").asString()).isEqualTo(buy.toString());
        assertThat(new BigDecimal(link.path("quantity").asString())).isEqualByComparingTo("10");

        decision = send(get("/api/v1/decisions/" + id), null, 200);
        decision = send(delete("/api/v1/decisions/" + id + "/transactions/" + link.path("id").asString())
                .param("expectedVersion", version(decision)).param("reason", "Giao dịch đã xóa"), null, 200);
        assertThat(decision.path("transactions").get(0).path("removedAt").isNull()).isFalse();
        JsonNode history = send(get("/api/v1/decisions/" + id + "/history"), null, 200);
        assertThat(history.get(history.size() - 1).path("kind").asString()).isEqualTo("TRANSACTION_UNLINKED");
    }

    @Test
    void staleAndConcurrentWritesConflict() throws Exception {
        JsonNode decision = send(post("/api/v1/decisions"), body(decision("BUY", fpt, portfolio, null)), 201);
        String id = id(decision);
        int version = decision.path("version").asInt();
        send(put("/api/v1/decisions/" + id), body(withTitle(decision("BUY", fpt, portfolio, version), "Bản A")), 200);
        JsonNode conflict = error(put("/api/v1/decisions/" + id), body(withTitle(decision("BUY", fpt, portfolio, version), "Bản B")), 409,
                "DECISION_CONFLICT");
        assertThat(conflict.path("currentVersion").asInt()).isGreaterThan(version);
        error(post("/api/v1/decisions/" + id + "/finalize"), transition(decision, null), 409, "DECISION_CONFLICT");

        JsonNode current = send(get("/api/v1/decisions/" + id), null, 200);
        int seen = current.path("version").asInt();
        UUID decisionId = UUID.fromString(id);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String title : List.of("Song song 1", "Song song 2")) {
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        decisionService.update(decisionId, command(title, seen));
                        return true;
                    } catch (ApiException e) {
                        assertThat(e.getCode().name()).isEqualTo("DECISION_CONFLICT");
                        return false;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                succeeded += result.get() ? 1 : 0;
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        JsonNode history = send(get("/api/v1/decisions/" + id + "/history"), null, 200);
        assertThat(history.size()).isEqualTo(3);
    }

    @Test
    void listFiltersByTickerPortfolioStatusTypeAndDate() throws Exception {
        JsonNode a = send(post("/api/v1/decisions"), body(withDate(decision("BUY", fpt, portfolio, null), today.minusDays(10))), 201);
        send(post("/api/v1/decisions"), body(withDate(decision("HOLD", vnm, portfolio, null), today.minusDays(5))), 201);
        send(post("/api/v1/decisions"), body(withDate(decision("OBSERVE", hpg, null, null), today)), 201);
        send(post("/api/v1/decisions/" + id(a) + "/finalize"), transition(a, null), 200);

        assertThat(list("").path("totalElements").asInt()).isEqualTo(3);
        assertThat(list("").path("content").get(0).path("symbol").asString()).isEqualTo("HPG");
        assertThat(list("?ticker=fpt").path("content").get(0).path("id").asString()).isEqualTo(id(a));
        assertThat(list("?portfolioId=" + portfolio).path("totalElements").asInt()).isEqualTo(2);
        assertThat(list("?status=DECIDED").path("totalElements").asInt()).isEqualTo(1);
        assertThat(list("?type=OBSERVE").path("totalElements").asInt()).isEqualTo(1);
        assertThat(list("?from=" + today.minusDays(6) + "&to=" + today.minusDays(1)).path("content").get(0).path("symbol").asString())
                .isEqualTo("VNM");
        JsonNode page = list("?size=2&page=1");
        assertThat(page.path("content").size()).isEqualTo(1);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
    }

    // --- helpers ---

    private Map<String, Object> decision(String type, UUID instrument, UUID portfolioId, Integer expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("portfolioId", portfolioId);
        body.put("instrumentId", instrument);
        body.put("decisionType", type);
        body.put("decisionDate", today.toString());
        body.put("title", "Mua thêm FPT");
        body.put("rationale", "Luận điểm tổng hợp");
        body.put("expectedOutcome", "Kết quả kỳ vọng");
        body.put("timeHorizon", "12 tháng");
        body.put("risks", "Rủi ro tổng hợp");
        body.put("invalidationConditions", "Điều kiện sai");
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private static Map<String, Object> withTitle(Map<String, Object> body, String title) {
        body.put("title", title);
        return body;
    }

    private static Map<String, Object> withDate(Map<String, Object> body, LocalDate date) {
        body.put("decisionDate", date.toString());
        return body;
    }

    private DecisionCommand command(String title, int expectedVersion) {
        return new DecisionCommand(portfolio, fpt, DecisionType.BUY, today, title, "Luận điểm tổng hợp", "Kết quả kỳ vọng", "12 tháng",
                null, "Rủi ro tổng hợp", "Điều kiện sai", null, null, null, null, null, expectedVersion, null);
    }

    private String body(Map<String, Object> body) {
        return json.writeValueAsString(body);
    }

    private String transition(JsonNode decision, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", decision.path("version").asInt());
        body.put("reason", reason);
        return json.writeValueAsString(body);
    }

    private String evidence(JsonNode decision, String kind, UUID source) {
        return json.writeValueAsString(Map.of("kind", kind, "sourceId", source, "role", "SUPPORTING",
                "expectedVersion", decision.path("version").asInt()));
    }

    private String link(JsonNode decision, UUID transaction) {
        return json.writeValueAsString(Map.of("transactionId", transaction, "expectedVersion", decision.path("version").asInt()));
    }

    private static String version(JsonNode decision) {
        return String.valueOf(decision.path("version").asInt());
    }

    private static String id(JsonNode decision) {
        return decision.path("id").asString();
    }

    private JsonNode list(String query) throws Exception {
        return send(get("/api/v1/decisions" + query), null, 200);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode error(MockHttpServletRequestBuilder request, String body, int status, String code) throws Exception {
        JsonNode problem = send(request, body, status);
        assertThat(problem.path("code").asString()).as(problem.toString()).isEqualTo(code);
        return problem;
    }

    private UUID instrument(String symbol) {
        return instrumentService.create(new InstrumentCommand(symbol, symbol + " (tổng hợp)", InstrumentType.STOCK, Exchange.HOSE, null,
                List.of())).id();
    }

    private UUID trade(UUID portfolioId, UUID instrumentId, TransactionType type, String quantity, String price) {
        return portfolioService.addTransaction(portfolioId, new TransactionCommand(instrumentId, type, today.minusDays(30),
                new BigDecimal(quantity), new BigDecimal(price), BigDecimal.ZERO, BigDecimal.ZERO, null)).id();
    }

    private static JsonNode holding(JsonNode holdings, String symbol) {
        for (JsonNode holding : holdings) {
            if (holding.path("symbol").asString().equals(symbol)) {
                return holding;
            }
        }
        throw new AssertionError("no holding " + symbol);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-journal-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

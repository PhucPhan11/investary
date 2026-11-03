package com.investary.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.investary.document.application.DocumentService;
import com.investary.email.application.EmailImportService;
import com.investary.knowledge.FakeAiConfiguration;
import com.investary.knowledge.KnowledgeTestSupport;
import com.investary.knowledge.ScriptedChatModel;
import com.investary.knowledge.SyntheticResearchCorpus;
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
 * Portfolio intelligence end to end: the synthetic research corpus is indexed with the fake embedding
 * model, a synthetic portfolio is matched against it, and the scripted chat model plays the AI. No real
 * provider is called.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class IntelligenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    static final Path STORAGE = createTempDir();

    private static final Pattern HOLDING = Pattern.compile("<holding symbol=\"([A-Z0-9]+)\" allowed=\"([A-Z_,]+)\" evidence=\"([S0-9,]+)\"/>");
    private static final Pattern SOURCE = Pattern.compile("<source id=\"(S\\d+)\"[^\\n]*>\\n(.*?)\\n</source>", Pattern.DOTALL);

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

    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    private Map<String, UUID> corpus;
    private Map<String, UUID> instruments;
    private UUID portfolio;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from portfolios");
        jdbc.update("delete from instruments");
        jdbc.update("delete from investor_profile");
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        chatModel.reset();
        corpus = new SyntheticResearchCorpus(documents, emailImport).load();
        KnowledgeTestSupport.indexAll(queue, processor);

        portfolio = portfolioService.create(new PortfolioCommand("Danh mục tổng hợp", null, null)).id();
        instruments = new LinkedHashMap<>();
        instruments.put("SCS", instrument("SCS", "Dịch vụ Hàng hóa Sài Gòn (tổng hợp)"));
        instruments.put("FPT", instrument("FPT", "FPT (tổng hợp)"));
        instruments.put("HPG", instrument("HPG", "Hòa Phát (tổng hợp)"));
        instruments.put("VNM", instrument("VNM", "Vinamilk (tổng hợp)"));
        buy("SCS", "1000", "70000");
        buy("FPT", "100", "100000");
        buy("HPG", "500", "25000");
        buy("VNM", "50", "60000");
    }

    @Test
    void relevantResearchSeparatesDirectResearchFromMentionsAndOtherTickers() throws Exception {
        JsonNode research = postJson("/api/v1/intelligence/portfolios/" + portfolio + "/relevant-research", "{}");

        JsonNode scs = holding(research, "SCS");
        assertThat(sourceIds(scs.path("direct"))).contains(corpus.get("tcbs-scs-report"), corpus.get("ssi-scs-email"));
        List<String> dates = new ArrayList<>();
        scs.path("direct").forEach(e -> dates.add(e.path("publishedAt").asString()));
        assertThat(dates).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        JsonNode fpt = holding(research, "FPT");
        assertThat(sourceIds(fpt.path("direct"))).contains(corpus.get("vnd-fpt-report")).doesNotContain(corpus.get("tcbs-frt-report"));
        assertThat(sourceIds(fpt.path("mentions"))).doesNotContain(corpus.get("tcbs-frt-report"));
        assertThat(sourceIds(holding(research, "HPG").path("direct"))).contains(corpus.get("vcbs-injection-email"));
        assertThat(holding(research, "VNM").path("direct").isEmpty()).isTrue();
        research.path("marketContext").forEach(evidence -> assertThat(evidence.path("matchKind").asString()).isEqualTo("MARKET_CONTEXT"));
    }

    @Test
    void analysisIsDeterministicAndNeutral() throws Exception {
        JsonNode analysis = postJson("/api/v1/intelligence/portfolios/" + portfolio + "/analyze", null);

        assertThat(chatModel.prompts()).isEmpty();
        assertThat(analysis.path("summary").path("valuationStatus").asString()).isEqualTo("UNAVAILABLE");
        List<String> codes = new ArrayList<>();
        analysis.path("observations").forEach(o -> codes.add(o.path("code").asString() + ":" + o.path("symbol").asString("")));
        assertThat(codes).contains("CONCENTRATION:SCS", "VALUATION_UNAVAILABLE:", "PROFILE_MISSING:", "NO_GOALS:");
        assertThat(analysis.path("researchCoverage")).anySatisfy(c -> {
            assertThat(c.path("symbol").asString()).isEqualTo("SCS");
            assertThat(c.path("directCount").asInt()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void assessmentIsSavedValidatedAndBecomesStale() throws Exception {
        chatModel.reply(IntelligenceIntegrationTest::assessmentReply);
        int transactionsBefore = count("portfolio_transactions");

        JsonNode created = postJson("/api/v1/intelligence/portfolios/" + portfolio + "/impact-assessments", null);

        assertThat(created.path("aiStatus").asString()).isEqualTo("COMPLETED");
        assertThat(created.path("stale").asBoolean()).isFalse();
        JsonNode scs = item(created, "SCS");
        assertThat(scs.path("category").asString()).isEqualTo("POTENTIAL_RISK");
        assertThat(scs.path("aiInterpretation").asBoolean()).isTrue();
        assertThat(scs.path("evidence").get(0).path("chunkId").asString()).isNotBlank();
        JsonNode vnm = item(created, "VNM");
        // No research names VNM: at most market context, never a holding-specific risk or opportunity.
        assertThat(vnm.path("deterministicCategory").asString()).isIn("INSUFFICIENT_EVIDENCE", "MARKET_CONTEXT");
        assertThat(vnm.path("category").asString()).isIn("INSUFFICIENT_EVIDENCE", "MARKET_CONTEXT", "MONITORING_ONLY");
        vnm.path("evidence").forEach(e -> assertThat(e.path("matchKind").asString()).isEqualTo("MARKET_CONTEXT"));
        // The injected instruction was data in a <source>, never an instruction.
        String system = chatModel.lastPrompt().getInstructions().getFirst().getText();
        assertThat(system).doesNotContain("Ignore all previous instructions");
        assertThat(chatModel.lastPrompt().getInstructions().get(1).getText()).contains("Ignore all previous instructions");
        // The AI never changes portfolio data.
        assertThat(count("portfolio_transactions")).isEqualTo(transactionsBefore);

        String id = created.path("id").asString();
        mvc.perform(get("/api/v1/intelligence/impact-assessments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale", is(false)))
                .andExpect(jsonPath("$.snapshot.holdings.length()", is(4)));

        buy("FPT", "10", "105000");
        mvc.perform(get("/api/v1/intelligence/impact-assessments/{id}", id))
                .andExpect(jsonPath("$.stale", is(true)))
                .andExpect(jsonPath("$.staleReasons", hasItem("HOLDINGS_CHANGED")));

        documents.delete(corpus.get("tcbs-scs-report"));
        documents.delete(corpus.get("vnd-fpt-report"));
        mvc.perform(get("/api/v1/intelligence/portfolios/{id}/impact-assessments", portfolio))
                .andExpect(jsonPath("$[0].id", is(id)))
                .andExpect(jsonPath("$[0].staleReasons", hasItem("EVIDENCE_CHANGED")));
    }

    @Test
    void unparseableModelOutputKeepsTheDeterministicAssessment() throws Exception {
        chatModel.reply("không phải JSON");

        JsonNode created = postJson("/api/v1/intelligence/portfolios/" + portfolio + "/impact-assessments", "{\"brokerage\": \"SSI\"}");

        assertThat(item(created, "SCS").path("category").asString()).isEqualTo("DIRECT_HOLDING");
        assertThat(item(created, "FPT").path("category").asString()).isIn("INSUFFICIENT_EVIDENCE", "MARKET_CONTEXT");
        assertThat(created.path("warnings").toString()).contains("không đúng định dạng");
    }

    @Test
    void portfolioAwareQuestionsNeedAPortfolioAndKeepFactsSeparate() throws Exception {
        mvc.perform(post("/api/v1/intelligence/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"Danh mục của tôi?\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("PORTFOLIO_REQUIRED")))
                .andExpect(jsonPath("$.portfolioCount", is(1)));

        chatModel.reply(prompt -> {
            Map<String, String> sources = sources(prompt);
            String label = sources.keySet().iterator().next();
            return """
                    {"answer": "Dữ liệu danh mục: Bạn nắm giữ 1000 cổ phiếu SCS.\\nBằng chứng từ nguồn: %s [%s].\\nNhận xét của trợ lý: cần theo dõi thêm.",
                     "evidenceStatus": "SUPPORTED", "unsupportedPoints": []}""".formatted(sources.get(label).replace("\"", "'"), label);
        });
        JsonNode answer = postJson("/api/v1/intelligence/ask", "{\"question\": \"SCS ảnh hưởng thế nào tới danh mục?\", \"portfolioId\": \"" + portfolio + "\"}");

        assertThat(answer.path("evidenceStatus").asString()).isEqualTo("SUPPORTED");
        assertThat(answer.path("focusSymbols").get(0).asString()).isEqualTo("SCS");
        assertThat(answer.path("portfolioFacts").asString()).contains("SCS").contains("1000");
        assertThat(answer.path("citations").get(0).path("sourceId").asString()).isNotBlank();
        assertThat(answer.path("holdings").size()).isEqualTo(4);
    }

    /** Scripted model: for each holding, quote the first line of its first source and call it a risk when allowed. */
    private static String assessmentReply(Prompt prompt) {
        String user = prompt.getInstructions().get(1).getText();
        Map<String, String> sources = sources(prompt);
        StringBuilder items = new StringBuilder();
        Matcher holding = HOLDING.matcher(user);
        while (holding.find()) {
            String label = holding.group(3).split(",")[0];
            String category = holding.group(2).contains("POTENTIAL_RISK") ? "POTENTIAL_RISK" : "MONITORING_ONLY";
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("{\"symbol\": \"").append(holding.group(1)).append("\", \"category\": \"").append(category)
                    .append("\", \"summary\": \"").append(sources.get(label).replace("\"", "'")).append(" [").append(label).append("]\"}");
        }
        return "{\"overall\": \"Nhận xét của trợ lý: cần đọc thêm nguồn.\", \"items\": [" + items + "]}";
    }

    private static Map<String, String> sources(Prompt prompt) {
        Map<String, String> sources = new LinkedHashMap<>();
        Matcher source = SOURCE.matcher(prompt.getInstructions().get(1).getText());
        while (source.find()) {
            String firstLine = source.group(2).lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
            sources.put(source.group(1), firstLine.replace("\\", ""));
        }
        return sources;
    }

    private UUID instrument(String symbol, String name) {
        return instrumentService.create(new InstrumentCommand(symbol, name, InstrumentType.STOCK, Exchange.HOSE, null, List.of())).id();
    }

    private void buy(String symbol, String quantity, String price) {
        portfolioService.addTransaction(portfolio, new TransactionCommand(instruments.get(symbol), TransactionType.BUY, today.minusDays(30),
                new BigDecimal(quantity), new BigDecimal(price), BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    private JsonNode postJson(String path, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static JsonNode holding(JsonNode research, String symbol) {
        for (JsonNode holding : research.path("holdings")) {
            if (holding.path("symbol").asString().equals(symbol)) {
                return holding;
            }
        }
        throw new AssertionError("no holding " + symbol);
    }

    private static JsonNode item(JsonNode assessment, String symbol) {
        for (JsonNode item : assessment.path("items")) {
            if (item.path("symbol").asString().equals(symbol)) {
                return item;
            }
        }
        throw new AssertionError("no item " + symbol);
    }

    private static List<UUID> sourceIds(JsonNode evidence) {
        List<UUID> ids = new ArrayList<>();
        evidence.forEach(e -> ids.add(UUID.fromString(e.path("sourceId").asString())));
        return ids;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-intelligence-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.investary.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.investary.document.application.DocumentService;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.document.domain.ReportType;
import com.investary.email.application.EmailImportService;
import com.investary.knowledge.FakeAiConfiguration;
import com.investary.knowledge.KnowledgeTestSupport;
import com.investary.knowledge.ScriptedChatModel;
import com.investary.knowledge.SyntheticResearchCorpus;
import com.investary.knowledge.TestResearchPdfs;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.notebook.application.NoteJobProcessor;
import com.investary.notebook.persistence.NoteQueue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Research notebooks end to end on PostgreSQL: scoped chat with history, generated notes with validated citations,
 * reuse of identical notes, retries, and citations that outlive a deleted source. All data is synthetic; the models are fakes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class NotebookIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    static final Path STORAGE = createTempDir();

    private static final Pattern SOURCE = Pattern.compile("<source id=\"(S\\d+)\"[^>]*>\\n(.*?)\\n</source>", Pattern.DOTALL);
    private static final Pattern GROUP = Pattern.compile("^(G\\d+): (S\\d+)", Pattern.MULTILINE);

    private static Map<String, UUID> corpus;

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
    KnowledgeIndexQueue indexQueue;

    @Autowired
    KnowledgeIndexProcessor indexProcessor;

    @Autowired
    ScriptedChatModel chatModel;

    @Autowired
    NoteQueue noteQueue;

    @Autowired
    NoteJobProcessor noteProcessor;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        jdbc.update("delete from notebooks");
        if (corpus == null) {
            corpus = new SyntheticResearchCorpus(documents, emailImport).load();
            KnowledgeTestSupport.indexAll(indexQueue, indexProcessor);
        }
        chatModel.reset();
    }

    @Test
    void chatUsesOnlyTheNotebookSourcesAndKeepsHistory() throws Exception {
        UUID notebook = notebook("SCS");
        JsonNode detail = send(post("/api/v1/notebooks/" + notebook + "/sources"),
                Map.of("documentIds", List.of(corpus.get("tcbs-scs-report")), "messageIds", List.of(corpus.get("ssi-scs-email"))), 200);
        assertThat(detail.path("sources")).hasSize(2);
        assertThat(detail.path("searchableSources").asInt()).isEqualTo(2);
        assertThat(texts(detail.path("tickers"))).contains("SCS");

        // FPT research exists in the library but not in this notebook: no evidence, so no model call.
        JsonNode outside = send(post("/api/v1/notebooks/" + notebook + "/ask"),
                Map.of("question", "Mảng chuyển đổi số của FPT tăng trưởng bao nhiêu?"), 200);
        assertThat(outside.path("answer").path("evidenceStatus").asString()).isEqualTo("INSUFFICIENT");
        assertThat(chatModel.prompts()).isEmpty();

        chatModel.reply(prompt -> {
            String label = labelContaining(prompt, "80.000");
            return "{\"answer\":\"Theo TCBS, khuyến nghị Mua với giá mục tiêu 80.000 đồng [" + label + "].\",\"evidenceStatus\":\"SUPPORTED\"}";
        });
        JsonNode first = send(post("/api/v1/notebooks/" + notebook + "/ask"), Map.of("question", "Khuyến nghị của TCBS cho SCS là gì?"), 200);
        assertThat(first.path("answer").path("evidenceStatus").asString()).isEqualTo("SUPPORTED");
        JsonNode citations = first.path("answer").path("citations");
        assertThat(citations).isNotEmpty();
        Set<String> allowed = Set.of(corpus.get("tcbs-scs-report").toString(), corpus.get("ssi-scs-email").toString());
        citations.forEach(c -> assertThat(allowed).contains(c.path("sourceId").asString()));
        assertThat(citations.get(0).path("available").asBoolean()).isTrue();
        String firstPrompt = chatModel.lastPrompt().getContents();
        assertThat(firstPrompt).doesNotContain("Khả quan").doesNotContain("HPG");

        chatModel.reply(prompt -> "{\"answer\":\"Theo SSI, khuyến nghị Bán với giá mục tiêu 60.000 đồng [" + labelContaining(prompt, "60.000")
                + "].\",\"evidenceStatus\":\"SUPPORTED\"}");
        send(post("/api/v1/notebooks/" + notebook + "/ask"), Map.of("question", "Còn SSI thì sao?"), 200);
        String followUp = chatModel.lastPrompt().getContents();
        assertThat(followUp).contains("<previous_turn>").contains("Khuyến nghị của TCBS cho SCS là gì?");
        // Old citation markers are stripped from the history: they meant other sources.
        assertThat(followUp.substring(followUp.indexOf("<previous_turn>"), followUp.lastIndexOf("</previous_turn>")))
                .doesNotContainPattern("\\[S\\d+]");

        JsonNode messages = send(get("/api/v1/notebooks/" + notebook + "/messages"), null, 200);
        assertThat(messages).hasSize(6);
        assertThat(messages.get(0).path("role").asString()).isEqualTo("USER");
        assertThat(messages.get(3).path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(messages.get(3).path("citations")).isNotEmpty();

        send(delete("/api/v1/notebooks/" + notebook + "/messages"), null, 204);
        assertThat(send(get("/api/v1/notebooks/" + notebook + "/messages"), null, 200)).isEmpty();
    }

    @Test
    void emptyNotebookCannotAnswerOrGenerate() throws Exception {
        UUID notebook = notebook("Trống");
        JsonNode error = send(post("/api/v1/notebooks/" + notebook + "/ask"), Map.of("question", "Có gì mới?"), 422);
        assertThat(error.path("code").asString()).isEqualTo("NOTEBOOK_EMPTY");
        error = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "SUMMARY"), 422);
        assertThat(error.path("code").asString()).isEqualTo("NOTEBOOK_EMPTY");
        error = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "CONSENSUS"), 400);
        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void filterAddsMatchingSourcesOnceAndVersionGuardsUpdates() throws Exception {
        UUID notebook = notebook("TCBS");
        JsonNode detail = send(post("/api/v1/notebooks/" + notebook + "/sources"), Map.of("filter", Map.of("brokerage", "TCBS")), 200);
        List<String> ids = new ArrayList<>();
        detail.path("sources").forEach(s -> ids.add(s.path("sourceId").asString()));
        assertThat(ids).contains(corpus.get("tcbs-scs-report").toString(), corpus.get("tcbs-frt-report").toString())
                .doesNotContain(corpus.get("vnd-fpt-report").toString());

        int version = detail.path("version").asInt();
        send(patch("/api/v1/notebooks/" + notebook), Map.of("title", "TCBS 2026", "expectedVersion", version), 200);
        JsonNode conflict = send(patch("/api/v1/notebooks/" + notebook), Map.of("title", "Cũ", "expectedVersion", version), 409);
        assertThat(conflict.path("code").asString()).isEqualTo("NOTEBOOK_CONFLICT");

        detail = send(delete("/api/v1/notebooks/" + notebook + "/sources/" + corpus.get("tcbs-frt-report")), null, 200);
        assertThat(detail.path("sources").toString()).doesNotContain(corpus.get("tcbs-frt-report").toString());
    }

    @Test
    void consensusNoteKeepsStoredFactsDropsTradingTextAndIsReused() throws Exception {
        UUID notebook = notebook("SCS so sánh");
        send(post("/api/v1/notebooks/" + notebook + "/sources"), Map.of(
                "documentIds", List.of(corpus.get("tcbs-scs-report"), corpus.get("vnd-fpt-report")),
                "messageIds", List.of(corpus.get("ssi-scs-email"))), 200);

        chatModel.reply(prompt -> {
            Map<String, String> sources = sources(prompt);
            List<Map<String, String>> views = new ArrayList<>();
            Matcher groups = GROUP.matcher(prompt.getContents());
            while (groups.find()) {
                String label = groups.group(2);
                views.add(Map.of("group", groups.group(1), "view", firstSentence(sources.get(label)) + " [" + label + "]"));
            }
            String all = String.join(", ", sources.keySet());
            return json.writeValueAsString(Map.of(
                    "views", views,
                    "agreements", List.of("Sản lượng hàng hóa là yếu tố được các nguồn theo dõi [" + all + "]."),
                    "disagreements", List.of("Nhà đầu tư nên mua SCS ngay [" + sources.keySet().iterator().next() + "].")));
        });

        JsonNode requested = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "CONSENSUS", "ticker", "scs"), 202);
        assertThat(requested.path("status").asString()).isEqualTo("PENDING");
        assertThat(requested.path("ticker").asString()).isEqualTo("SCS");
        runNotes();

        JsonNode note = send(get("/api/v1/notebooks/" + notebook + "/notes/" + requested.path("id").asString()), null, 200);
        assertThat(note.path("status").asString()).isEqualTo("DONE");
        JsonNode entries = note.path("content").path("sources");
        List<String> sourceIds = new ArrayList<>();
        entries.forEach(e -> sourceIds.add(e.path("sourceId").asString()));
        assertThat(sourceIds).containsExactlyInAnyOrder(corpus.get("tcbs-scs-report").toString(), corpus.get("ssi-scs-email").toString());
        entries.forEach(e -> {
            if (e.path("sourceId").asString().equals(corpus.get("ssi-scs-email").toString())) {
                assertThat(e.path("rating").asString()).isEqualTo("Bán"); // copied from the extracted research item
                assertThat(e.path("brokerage").asString()).isNotBlank();
            }
            assertThat(e.path("view").path("labels")).isNotEmpty();
        });
        assertThat(note.path("content").path("agreements")).hasSize(1);
        assertThat(note.path("content").path("disagreements")).isEmpty();
        assertThat(note.path("limitations").toString()).contains("lệnh giao dịch");
        assertThat(note.path("evidenceStatus").asString()).isEqualTo("PARTIAL");
        assertThat(note.path("citations")).isNotEmpty();
        assertThat(chatModel.lastPrompt().getContents()).doesNotContain("Khả quan"); // FPT report is not about SCS

        // Same inputs: the earlier note is returned and the model is not called again.
        int calls = chatModel.prompts().size();
        JsonNode again = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "CONSENSUS", "ticker", "SCS"), 200);
        assertThat(again.path("reused").asBoolean()).isTrue();
        assertThat(again.path("id").asString()).isEqualTo(requested.path("id").asString());
        runNotes();
        assertThat(chatModel.prompts()).hasSize(calls);
    }

    @Test
    void summaryAndFaqDropUncitedItems() throws Exception {
        UUID notebook = notebook("Tổng hợp");
        send(post("/api/v1/notebooks/" + notebook + "/sources"),
                Map.of("documentIds", List.of(corpus.get("tcbs-scs-report"), corpus.get("ssi-bank-sector"))), 200);

        chatModel.reply(prompt -> {
            Map<String, String> sources = sources(prompt);
            String label = sources.keySet().iterator().next();
            String quote = firstSentence(sources.get(label)) + " [" + label + "]";
            return prompt.getContents().contains("\"items\"") || prompt.getInstructions().getFirst().getText().contains("\"items\"")
                    ? json.writeValueAsString(Map.of("items", List.of(
                            Map.of("question", "Báo cáo nói gì? [S1]", "answer", quote),
                            Map.of("question", "Không có nguồn?", "answer", "Không có trích dẫn."))))
                    : json.writeValueAsString(Map.of("points", List.of(Map.of("text", "Ý không có trích dẫn."), Map.of("text", quote))));
        });

        JsonNode summary = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "SUMMARY"), 202);
        JsonNode faq = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "FAQ"), 202);
        runNotes();

        summary = send(get("/api/v1/notebooks/" + notebook + "/notes/" + summary.path("id").asString()), null, 200);
        assertThat(summary.path("content").path("points")).hasSize(1);
        assertThat(summary.path("evidenceStatus").asString()).isEqualTo("PARTIAL");
        faq = send(get("/api/v1/notebooks/" + notebook + "/notes/" + faq.path("id").asString()), null, 200);
        JsonNode items = faq.path("content").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("question").asString()).isEqualTo("Báo cáo nói gì?"); // marker removed from the question

        JsonNode list = send(get("/api/v1/notebooks/" + notebook + "/notes"), null, 200);
        assertThat(list).hasSize(2);
    }

    @Test
    void unreadableRepliesAreRetriedThenFailedAndCanBeRetriedByTheUser() throws Exception {
        UUID notebook = notebook("Lỗi");
        send(post("/api/v1/notebooks/" + notebook + "/sources"), Map.of("documentIds", List.of(corpus.get("tcbs-frt-report"))), 200);
        chatModel.reply("not json at all");

        JsonNode note = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "SUMMARY"), 202);
        runNotes();
        note = send(get("/api/v1/notebooks/" + notebook + "/notes/" + note.path("id").asString()), null, 200);
        assertThat(note.path("status").asString()).isEqualTo("FAILED");
        assertThat(note.path("errorSummary").asString()).isNotBlank();
        assertThat(chatModel.prompts()).hasSize(3);

        JsonNode retried = send(post("/api/v1/notebooks/" + notebook + "/notes/" + note.path("id").asString() + "/retry"), null, 202);
        assertThat(retried.path("status").asString()).isEqualTo("PENDING");
        JsonNode notRetryable = send(post("/api/v1/notebooks/" + notebook + "/notes/" + note.path("id").asString() + "/retry"), null, 409);
        assertThat(notRetryable.path("code").asString()).isEqualTo("NOTE_NOT_RETRYABLE");
    }

    @Test
    void citationsOutliveADeletedSource() throws Exception {
        UUID document = documents.upload(new UploadDocumentCommand(new ByteArrayResource(TestResearchPdfs.textPdf(List.of(List.of(
                "ACB - Báo cáo cập nhật (dữ liệu tổng hợp)",
                "Tăng trưởng tín dụng của ngân hàng đạt 14% trong chín tháng.")))),
                "ACB_synthetic.pdf", "MBS", ReportType.STOCK_ANALYSIS, "ACB", LocalDate.parse("2026-10-05"))).getId();
        KnowledgeTestSupport.indexAll(indexQueue, indexProcessor);
        UUID notebook = notebook("ACB");
        send(post("/api/v1/notebooks/" + notebook + "/sources"), Map.of("documentIds", List.of(document)), 200);
        chatModel.reply(prompt -> {
            Map<String, String> sources = sources(prompt);
            String label = sources.keySet().iterator().next();
            return json.writeValueAsString(Map.of("points", List.of(Map.of("text", firstSentence(sources.get(label)) + " [" + label + "]"))));
        });
        JsonNode note = send(post("/api/v1/notebooks/" + notebook + "/notes"), Map.of("kind", "SUMMARY"), 202);
        runNotes();

        documents.delete(document);

        note = send(get("/api/v1/notebooks/" + notebook + "/notes/" + note.path("id").asString()), null, 200);
        JsonNode citation = note.path("citations").get(0);
        assertThat(citation.path("available").asBoolean()).isFalse();
        assertThat(citation.path("sourceId").isNull()).isTrue();
        assertThat(citation.path("excerpt").asString()).contains("14%");
        assertThat(send(get("/api/v1/notebooks/" + notebook), null, 200).path("sources")).isEmpty();
    }

    // ---- helpers ----

    private UUID notebook(String title) throws Exception {
        return UUID.fromString(send(post("/api/v1/notebooks"), Map.of("title", title), 201).path("id").asString());
    }

    private void runNotes() {
        for (int round = 0; round < 10; round++) {
            List<NoteQueue.ClaimedNote> claimed = noteQueue.claim(10);
            if (claimed.isEmpty()) {
                try {
                    Thread.sleep(20); // retries are scheduled a millisecond or so ahead
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                claimed = noteQueue.claim(10);
                if (claimed.isEmpty()) {
                    return;
                }
            }
            claimed.forEach(noteProcessor::process);
        }
    }

    private JsonNode send(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var result = mvc.perform(request).andReturn().getResponse();
        result.setCharacterEncoding("UTF-8");
        String response = result.getContentAsString();
        assertThat(result.getStatus()).as(response).isEqualTo(status);
        return response.isBlank() ? json.createObjectNode() : json.readTree(response);
    }

    private static Map<String, String> sources(Prompt prompt) {
        Map<String, String> sources = new LinkedHashMap<>();
        Matcher matcher = SOURCE.matcher(prompt.getContents());
        while (matcher.find()) {
            sources.put(matcher.group(1), matcher.group(2));
        }
        return sources;
    }

    private static String labelContaining(Prompt prompt, String text) {
        return sources(prompt).entrySet().stream().filter(e -> e.getValue().contains(text)).map(Map.Entry::getKey).findFirst().orElse("S1");
    }

    private static String firstSentence(String text) {
        String line = text.strip().split("\\n")[0];
        int dot = line.indexOf(". ");
        return (dot > 0 ? line.substring(0, dot + 1) : line).strip();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-notebook-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.investary.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import com.investary.email.TestEmails;
import com.investary.email.application.EmailDeletionService;
import com.investary.email.application.EmailImportService;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.knowledge.index.KnowledgeIndexService;
import com.investary.knowledge.index.KnowledgeJob;
import com.investary.knowledge.index.KnowledgeSourceStatus;
import com.investary.knowledge.index.ReindexScope;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;

/**
 * End-to-end knowledge base behaviour on PostgreSQL + pgvector with fake AI models: indexing, failure
 * handling, versioning, deletion, retrieval and the assistant API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeBaseIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    static final Path STORAGE = createTempDir();

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
    EmailDeletionService emailDeletion;

    @Autowired
    KnowledgeIndexQueue queue;

    @Autowired
    KnowledgeIndexProcessor processor;

    @Autowired
    KnowledgeIndexService indexService;

    @Autowired
    HybridSearchService search;

    @Autowired
    FakeEmbeddingModel embeddingModel;

    @Autowired
    ScriptedChatModel chatModel;

    Map<String, UUID> corpus;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        embeddingModel.reset();
        chatModel.reset();
        corpus = new SyntheticResearchCorpus(documents, emailImport).load();
    }

    @Test
    void backfillIndexesExistingDocumentsAndEmailsWithCitableLocations() {
        indexAll();

        assertThat(jdbc.queryForObject("select count(*) from knowledge_sources where status = 'INDEXED'", Integer.class))
                .isEqualTo(corpus.size());
        KnowledgeJob report = job("tcbs-scs-report");
        assertThat(report.pageCount()).isEqualTo(2);
        assertThat(report.chunkCount()).isPositive();
        assertThat(indexService.isCurrent(report)).isTrue();

        Map<String, Object> chunk = jdbc.queryForMap("""
                select c.page_start, c.page_end, c.brokerage, c.published_on, c.date_basis, c.source_ticker,
                       vector_dims(c.embedding) as dims, c.embedding_model
                from knowledge_chunks c join knowledge_sources s on s.id = c.knowledge_source_id
                where s.document_id = ?""", corpus.get("tcbs-scs-report"));
        assertThat(chunk.get("page_start")).isEqualTo(1);
        assertThat(chunk.get("page_end")).isEqualTo(2);
        assertThat(chunk.get("brokerage")).isEqualTo("TCBS");
        assertThat(chunk.get("date_basis")).isEqualTo("PUBLISHED");
        assertThat(chunk.get("source_ticker")).isEqualTo("SCS");
        assertThat(chunk.get("dims")).isEqualTo(FakeEmbeddingModel.DIMENSIONS);
        assertThat(chunk.get("embedding_model")).isEqualTo("fake:hashed-bag-of-words");

        List<String> sections = jdbc.queryForList("""
                select c.section from knowledge_chunks c join knowledge_sources s on s.id = c.knowledge_source_id
                where s.source_message_id = ? order by c.chunk_index""", String.class, corpus.get("tcbs-newsletter"));
        assertThat(sections).contains("3. Khuyến nghị cổ phiếu").noneMatch(s -> s != null && s.contains("ưu đãi"));
        // A long multi-topic newsletter is never labelled with a single ticker.
        assertThat(jdbc.queryForObject("""
                select count(distinct t) from knowledge_chunks c join knowledge_sources s on s.id = c.knowledge_source_id,
                unnest(c.tickers) t where s.source_message_id = ?""", Integer.class, corpus.get("tcbs-newsletter"))).isGreaterThan(1);
    }

    @Test
    void reindexingUnchangedSourcesIsIdempotentAndCallsNoModel() {
        indexAll();
        List<UUID> chunkIds = jdbc.queryForList("select id from knowledge_chunks order by id", UUID.class);
        int embedded = embeddingModel.embeddedTexts();

        assertThat(indexService.reindex(ReindexScope.ALL)).isEqualTo(corpus.size());
        KnowledgeTestSupport.processQueued(queue, processor);

        assertThat(embeddingModel.embeddedTexts()).isEqualTo(embedded);
        assertThat(jdbc.queryForList("select id from knowledge_chunks order by id", UUID.class)).isEqualTo(chunkIds);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_sources where status = 'INDEXED'", Integer.class))
                .isEqualTo(corpus.size());
    }

    @Test
    void staleIndexVersionIsNotSearchedUntilExplicitlyReindexed() {
        indexAll();
        jdbc.update("update knowledge_sources set index_version = 'p0-old'");
        jdbc.update("update knowledge_chunks set index_version = 'p0-old'");

        assertThat(search("rủi ro SCS").hits()).isEmpty();
        assertThat(indexService.status().staleSources()).isEqualTo(corpus.size());

        assertThat(indexService.reindex(ReindexScope.STALE)).isEqualTo(corpus.size());
        int embedded = embeddingModel.embeddedTexts();
        KnowledgeTestSupport.processQueued(queue, processor);

        // Same text and model: stored vectors are reused instead of calling the provider again.
        assertThat(embeddingModel.embeddedTexts()).isEqualTo(embedded);
        assertThat(search("rủi ro SCS").hits()).isNotEmpty();
        assertThat(indexService.status().staleSources()).isZero();
    }

    @Test
    void transientEmbeddingFailureIsRetried() {
        embeddingModel.failNext(3); // more than the in-call retries, so the first job attempt fails

        indexAll();

        assertThat(job("vnd-fpt-report").status()).isEqualTo(KnowledgeSourceStatus.INDEXED);
        assertThat(jdbc.queryForObject("select max(attempts) from knowledge_sources", Integer.class)).isEqualTo(2);
    }

    @Test
    void persistentFailureEndsFailedAndCanBeRetried() {
        embeddingModel.failNext(1000);
        indexAll();

        KnowledgeJob failed = job("vnd-fpt-report");
        assertThat(failed.status()).isEqualTo(KnowledgeSourceStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.lastError()).contains("Simulated provider outage");

        embeddingModel.reset();
        assertThat(search("FPT chuyển đổi số").hits()).isEmpty();
        KnowledgeJob retried = indexService.request(KnowledgeSourceType.DOCUMENT, corpus.get("vnd-fpt-report"));
        assertThat(retried.status()).isEqualTo(KnowledgeSourceStatus.PENDING);
        KnowledgeTestSupport.processQueued(queue, processor);
        assertThat(job("vnd-fpt-report").status()).isEqualTo(KnowledgeSourceStatus.INDEXED);
    }

    @Test
    void dimensionMismatchFailsWithoutRetrying() {
        embeddingModel.returnDimensions(1024);

        indexAll();

        KnowledgeJob job = job("vnd-fpt-report");
        assertThat(job.status()).isEqualTo(KnowledgeSourceStatus.FAILED);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.lastError()).contains("1024");
        assertThat(jdbc.queryForObject("select count(*) from knowledge_chunks", Integer.class)).isZero();
    }

    @Test
    void scannedPdfIsSkippedAsNeedingOcr() {
        UUID scanned = documents.upload(new com.investary.document.application.UploadDocumentCommand(
                new org.springframework.core.io.ByteArrayResource(TestResearchPdfs.imageOnlyPdf(2)), "scan.pdf", "SSI",
                null, null, null)).getId();

        indexAll();

        KnowledgeJob job = indexService.forSource(KnowledgeSourceType.DOCUMENT, scanned).orElseThrow();
        assertThat(job.status()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(job.statusReason()).isEqualTo("OCR_REQUIRED");
        assertThat(job.chunkCount()).isZero();
    }

    @Test
    void lostClaimNeverWritesAPartialIndex() {
        queue.trackMissing(3);
        List<KnowledgeIndexQueue.ClaimedSource> claimed = queue.claim(50);
        jdbc.update("update knowledge_sources set claim_token = gen_random_uuid()"); // another worker took over

        claimed.forEach(processor::process);

        assertThat(jdbc.queryForObject("select count(*) from knowledge_chunks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from knowledge_sources where status = 'INDEXED'", Integer.class)).isZero();
    }

    @Test
    void concurrentWorkersNeverClaimTheSameSource() throws Exception {
        queue.trackMissing(3);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<KnowledgeIndexQueue.ClaimedSource>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit((Callable<List<KnowledgeIndexQueue.ClaimedSource>>) () -> queue.claim(3)));
            }
            List<UUID> ids = new ArrayList<>();
            for (var future : futures) {
                future.get().forEach(job -> ids.add(job.id()));
            }
            assertThat(ids).hasSize(corpus.size()).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingAnEmailKeepsTheSharedDocumentButDeletingTheDocumentRemovesItsChunks() {
        byte[] pdf = TestResearchPdfs.textPdf(List.of(List.of("Báo cáo đính kèm về HPG (tổng hợp)", "Sản lượng thép tăng mạnh.")));
        UUID email = emailImport.importEml(new MockMultipartFile("file", "a.eml", "message/rfc822", TestEmails.builder()
                .from("Research <research@ssi.com.vn>").subject("Báo cáo HPG").html("<p>Xem báo cáo HPG đính kèm.</p>")
                .attachment("HPG.pdf", "application/pdf", pdf).build()));
        UUID attached = jdbc.queryForObject("select document_id from message_documents where source_message_id = ?", UUID.class, email);
        indexAll();
        assertThat(chunks(attached)).isPositive();

        emailDeletion.delete(email);

        assertThat(indexService.forSource(KnowledgeSourceType.DOCUMENT, attached)).isPresent();
        assertThat(chunks(attached)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from knowledge_sources where source_message_id = ?", Integer.class, email)).isZero();

        documents.delete(attached);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_sources where document_id = ?", Integer.class, attached)).isZero();
        assertThat(search("thép HPG").hits()).noneMatch(hit -> hit.sourceId().equals(attached));
    }

    @Test
    void searchMatchesExactTickersAndAppliesFilters() {
        indexAll();

        assertThat(search("rủi ro của cổ phiếu SCS là gì").hits())
                .isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.tickers()).contains("SCS"));
        assertThat(search("rủi ro của cổ phiếu SCS là gì").hits()).extracting(SearchHit::sourceId).contains(corpus.get("tcbs-scs-report"));

        assertThat(search("khuyến nghị", new SearchFilters("FPT", null, null, null, null)).hits())
                .extracting(SearchHit::sourceId).containsOnly(corpus.get("vnd-fpt-report"));
        assertThat(search("khuyến nghị SCS", new SearchFilters(null, "SSI", null, null, null)).hits())
                .extracting(SearchHit::brokerage).containsOnly("SSI");
        assertThat(search("khuyến nghị SCS", new SearchFilters(null, null, KnowledgeSourceType.EMAIL, null, null)).hits())
                .isNotEmpty().extracting(SearchHit::sourceType).containsOnly(KnowledgeSourceType.EMAIL);
        assertThat(search("khuyến nghị SCS", new SearchFilters(null, null, null, LocalDate.parse("2026-10-24"), null)).hits())
                .isNotEmpty().allSatisfy(hit -> assertThat(hit.publishedOn()).isAfterOrEqualTo(LocalDate.parse("2026-10-24")));
    }

    @Test
    void comparisonRetrievesEachBrokerageAndReportsMissingOnes() {
        indexAll();

        SearchResult result = search("So sánh nhận định của TCBS, SSI và VCBS về SCS");

        assertThat(result.hits()).extracting(SearchHit::brokerage).contains("TCBS", "SSI");
        assertThat(result.coverageGaps()).containsExactly("VCBS");
    }

    @Test
    void unrelatedQuestionReturnsNothingInsteadOfFiller() {
        indexAll();

        assertThat(search("Giá vàng thế giới tuần này thế nào?").hits()).isEmpty();
    }

    @Test
    void assistantApiReturnsValidatedCitationsOfRealRecords() throws Exception {
        indexAll();
        chatModel.reply("{\"answer\": \"Theo TCBS, rủi ro chính của SCS là cạnh tranh từ sân bay Long Thành [S1]. Một báo cáo khác [S12].\", \"evidenceStatus\": \"SUPPORTED\"}");

        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Những rủi ro chính của SCS theo báo cáo gần nhất là gì?\", \"brokerage\": \"TCBS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceStatus", is("PARTIAL")))
                .andExpect(jsonPath("$.citations", hasSize(1)))
                .andExpect(jsonPath("$.citations[0].label", is("S1")))
                .andExpect(jsonPath("$.citations[0].sourceType", is("DOCUMENT")))
                .andExpect(jsonPath("$.citations[0].sourceId", is(corpus.get("tcbs-scs-report").toString())))
                .andExpect(jsonPath("$.citations[0].publisher", is("TCBS")))
                .andExpect(jsonPath("$.citations[0].location", is("trang 1–2")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("S12"))));

        String prompt = chatModel.lastPrompt().getUserMessage().getText();
        assertThat(prompt).contains("<source id=\"S1\"").contains("sân bay Long Thành");
    }

    @Test
    void injectedInstructionsInAnEmailRemainSourceData() throws Exception {
        indexAll();
        chatModel.reply("{\"answer\": \"Theo VCBS, sản lượng thép của HPG tăng 10% [S1].\", \"evidenceStatus\": \"SUPPORTED\"}");

        mvc.perform(post("/api/v1/research/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"HPG sản lượng thép thế nào?\", \"brokerage\": \"VCBS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceStatus", is("SUPPORTED")));

        var prompt = chatModel.lastPrompt();
        assertThat(prompt.getSystemMessage().getText()).doesNotContain("Ignore all previous");
        assertThat(prompt.getUserMessage().getText()).contains("Ignore all previous instructions");
    }

    @Test
    void statusAndJobEndpointsReportIndexState() throws Exception {
        mvc.perform(get("/api/v1/knowledge/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.available", is(true)))
                .andExpect(jsonPath("$.untrackedSources", is(corpus.size())));

        UUID document = corpus.get("vnd-fpt-report");
        mvc.perform(get("/api/v1/knowledge/documents/{id}", document))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_INDEXED")));
        mvc.perform(post("/api/v1/knowledge/index/documents/{id}", document))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("PENDING")));
        KnowledgeTestSupport.processQueued(queue, processor);

        UUID jobId = job("vnd-fpt-report").id();
        mvc.perform(get("/api/v1/knowledge/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("INDEXED")))
                .andExpect(jsonPath("$.currentVersion", is(true)));
        mvc.perform(post("/api/v1/knowledge/index/documents/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("DOCUMENT_NOT_FOUND")));
        mvc.perform(post("/api/v1/knowledge/reindex").contentType(MediaType.APPLICATION_JSON).content("{\"scope\": \"MISSING\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued", is(corpus.size() - 1)));
    }

    private void indexAll() {
        KnowledgeTestSupport.indexAll(queue, processor);
    }

    private SearchResult search(String query) {
        return search(query, SearchFilters.NONE);
    }

    private SearchResult search(String query, SearchFilters filters) {
        return search.search(query, filters, 8);
    }

    private KnowledgeJob job(String key) {
        UUID id = corpus.get(key);
        KnowledgeSourceType type = key.contains("email") || key.contains("newsletter") ? KnowledgeSourceType.EMAIL : KnowledgeSourceType.DOCUMENT;
        return indexService.forSource(type, id).orElseThrow();
    }

    private int chunks(UUID documentId) {
        return jdbc.queryForObject("""
                select count(*) from knowledge_chunks c join knowledge_sources s on s.id = c.knowledge_source_id
                where s.document_id = ?""", Integer.class, documentId);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-knowledge-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.investary.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;
import com.investary.document.application.DocumentService;
import com.investary.email.application.EmailImportService;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchHit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the retrieval evaluation set ({@code knowledge/eval/questions.json}) against the synthetic corpus
 * and writes {@code target/knowledge-eval-report.md}. With the fake embedding model this measures the
 * retrieval pipeline (filters, ticker matching, lexical search, fusion, thresholds), not the semantic
 * quality of a real model; answer groundedness needs a real chat model and is not evaluated here.
 */
@SpringBootTest
@Import(FakeAiConfiguration.class)
@TestPropertySource(locations = "classpath:fake-ai.properties")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeEvaluationTest {

    private static final int TOP_K = 8;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path storage = Files.createTempDirectory("investary-knowledge-eval");
        registry.add("investary.storage.root", () -> storage.resolve("documents").toString());
        registry.add("investary.email.storage.root", () -> storage.resolve("emails").toString());
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DocumentService documents;

    @Autowired
    EmailImportService emails;

    @Autowired
    KnowledgeIndexQueue queue;

    @Autowired
    KnowledgeIndexProcessor processor;

    @Autowired
    HybridSearchService search;

    @Test
    void retrievalEvaluationSet() throws IOException {
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        Map<String, UUID> corpus = new SyntheticResearchCorpus(documents, emails).load();
        Map<UUID, String> keys = corpus.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        KnowledgeTestSupport.indexAll(queue, processor);

        List<String> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int expectedSources = 0;
        int foundSources = 0;
        for (JsonNode testCase : load().path("cases")) {
            SearchFilters filters = filters(testCase.path("filters"));
            List<SearchHit> hits = search.search(testCase.path("question").asString(), filters, TOP_K).hits();
            Set<String> retrieved = new HashSet<>();
            hits.forEach(hit -> retrieved.add(keys.getOrDefault(hit.sourceId(), "?")));

            List<String> problems = new ArrayList<>();
            for (JsonNode key : testCase.path("all")) {
                expectedSources++;
                if (retrieved.contains(key.asString())) {
                    foundSources++;
                } else {
                    problems.add("missing " + key.asString());
                }
            }
            if (!testCase.path("any").isEmpty()) {
                expectedSources++;
                boolean any = false;
                for (JsonNode key : testCase.path("any")) {
                    any |= retrieved.contains(key.asString());
                }
                if (any) {
                    foundSources++;
                } else {
                    problems.add("none of " + testCase.path("any"));
                }
            }
            for (JsonNode key : testCase.path("none")) {
                if (retrieved.contains(key.asString())) {
                    problems.add("unexpected " + key.asString());
                }
            }
            if (testCase.path("empty").asBoolean(false) && !hits.isEmpty()) {
                problems.add("expected no evidence, got " + retrieved);
            }
            String id = testCase.path("id").asString();
            if (!problems.isEmpty()) {
                failures.add(id + ": " + String.join("; ", problems));
            }
            rows.add("| %s | %s | %s | %s |".formatted(id, testCase.path("category").asString(),
                    problems.isEmpty() ? "pass" : "FAIL: " + String.join("; ", problems), String.join(", ", new java.util.TreeSet<>(retrieved))));
        }

        int cases = rows.size();
        int passed = cases - failures.size();
        String report = """
                # Knowledge retrieval evaluation (synthetic corpus, fake embedding model)

                Cases passed: %d/%d. Expected-source recall@%d: %d/%d.

                | Case | Category | Result | Retrieved sources |
                |---|---|---|---|
                %s
                """.formatted(passed, cases, TOP_K, foundSources, expectedSources, String.join("\n", rows));
        Path target = Path.of("target", "knowledge-eval-report.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, report);

        assertThat(failures).as(report).isEmpty();
    }

    private static SearchFilters filters(JsonNode node) {
        if (node.isMissingNode()) {
            return SearchFilters.NONE;
        }
        return new SearchFilters(text(node, "ticker"), text(node, "brokerage"),
                node.has("sourceType") ? KnowledgeSourceType.valueOf(node.path("sourceType").asString()) : null,
                node.has("from") ? LocalDate.parse(node.path("from").asString()) : null,
                node.has("to") ? LocalDate.parse(node.path("to").asString()) : null);
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.path(field).asString() : null;
    }

    private static JsonNode load() {
        try (InputStream in = KnowledgeEvaluationTest.class.getResourceAsStream("/knowledge/eval/questions.json")) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

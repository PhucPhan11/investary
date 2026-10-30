package com.investary.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.document.persistence.DocumentRepository;

/**
 * End-to-end through HTTP, PostgreSQL (Testcontainers, schema created by Flyway) and the local filesystem.
 * Skipped automatically when no Docker daemon is available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DocumentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("investary.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void flywayCreatedSchema() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void fullDocumentLifecycle() throws Exception {
        byte[] pdf = TestPdfs.pdf("lifecycle-" + UUID.randomUUID());

        // upload
        MvcResult created = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "VNM-2026Q3.pdf", "application/pdf", pdf))
                        .param("brokerageCompany", "VNDirect")
                        .param("reportType", "STOCK_ANALYSIS")
                        .param("ticker", "vnm")
                        .param("publishedOn", "2026-10-15"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("VNM"))
                .andExpect(jsonPath("$.fileSize").value(pdf.length))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        assertThat(repository.count()).isEqualTo(1);
        String storageKey = repository.findById(UUID.fromString(id)).orElseThrow().getStorageKey();
        assertThat(STORAGE_ROOT.resolve(storageKey)).exists();

        // list + get
        mvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerageCompany").value("VNDirect"));

        // download returns identical bytes
        mvc.perform(get("/api/v1/documents/{id}/download", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(pdf));

        // duplicate content is rejected and leaves no extra file
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "copy.pdf", "application/pdf", pdf)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingDocumentId").value(id));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(storedPdfCount()).isEqualTo(1);

        // delete removes metadata and file
        mvc.perform(delete("/api/v1/documents/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/{id}", id)).andExpect(status().isNotFound());
        assertThat(STORAGE_ROOT.resolve(storageKey)).doesNotExist();
    }

    @Test
    void invalidFileTypeIsRejectedWithoutSideEffects() throws Exception {
        long filesBefore = storedPdfCount();

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "fake.pdf", "application/pdf", TestPdfs.notPdf())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));

        assertThat(repository.count()).isZero();
        assertThat(storedPdfCount()).isEqualTo(filesBefore);
    }

    @Test
    void missingDocumentIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}/download", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private static long storedPdfCount() throws IOException {
        try (Stream<Path> files = Files.list(STORAGE_ROOT)) {
            return files.filter(p -> p.toString().endsWith(".pdf")).count();
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("investary-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

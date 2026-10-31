package com.investary.email;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.investary.TestDatabase;
import com.investary.document.TestPdfs;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.SourceLinkView;
import com.investary.research.link.LinkStatus;
import com.jayway.jsonpath.JsonPath;

/**
 * End-to-end: EML upload → parsing → links → background download from a controlled HTTP server →
 * document library → provenance, against PostgreSQL migrated by Flyway. Never contacts real websites.
 */
@SpringBootTest(properties = {
        "investary.download.worker.enabled=true",
        "investary.download.worker.poll-interval=200ms",
        "investary.download.allow-http=true",
        "investary.download.allow-private-networks=true",
        "investary.download.allow-any-port=true",
        "investary.download.additional-allowed-domains=localhost",
        "investary.download.max-attempts=2",
        "investary.download.retry-backoff=200ms"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class EmailIngestionIntegrationTest {

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
    ResearchQueryService research;

    @BeforeEach
    void clean() {
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
    }

    @Test
    void importsFixtureAndExposesMetadataLinksItemsAndSafeHtml() throws Exception {
        String id = importEml(TestEmails.fixture("tcbs-style-newsletter.eml"), "newsletter.eml");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Cơ hội đầu tư | 28/10/2026"))
                .andExpect(jsonPath("$.brokerage").value("TCBS"))
                .andExpect(jsonPath("$.parsingStatus").value("PARSED"))
                .andExpect(jsonPath("$.languages[0]").value("vi"))
                .andExpect(jsonPath("$.statistics.researchLinks").value(6));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/research-items", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.itemType == 'STOCK_RECOMMENDATION')].structuredData.sourceRecommendation").value("Chờ mua"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/html", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(containsString("<script"))));

        MvcResult original = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/original", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("message/rfc822")))
                .andReturn();
        assertThat(original.getResponse().getContentAsByteArray()).isEqualTo(TestEmails.fixture("tcbs-style-newsletter.eml"));

        mvc.perform(multipart("/api/v1/emails/import").file(file(TestEmails.fixture("tcbs-style-newsletter.eml"), "again.eml")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingEmailId").value(id));
    }

    @Test
    void downloadsSelectedReportIntoLibraryAndKeepsProvenanceAcrossEmails() throws Exception {
        byte[] pdf = TestPdfs.pdf("imp-" + UUID.randomUUID());
        brokerage.stubFor(get(urlEqualTo("/reports/20261028_IMP_Q3.pdf")).willReturn(aResponse()
                .withHeader("Content-Type", "application/pdf").withBody(pdf)));

        String first = importEml(newsletter("first", "/reports/20261028_IMP_Q3.pdf"), "first.eml");
        SourceLinkView link = linkByText(first, "IMP - Q3 Earnings Update");
        assertThat(link.status()).isEqualTo(LinkStatus.READY);

        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", first, link.id()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/links/" + link.id())));
        SourceLinkView downloaded = awaitStatus(first, link.id(), LinkStatus.DOWNLOADED);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/documents"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(downloaded.documentId().toString()))
                .andExpect(jsonPath("$.content[0].brokerageCompany").value("TCBS"))
                .andExpect(jsonPath("$.content[0].ticker").value("IMP"))
                .andExpect(jsonPath("$.content[0].reportType").value("STOCK_ANALYSIS"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/documents", first))
                .andExpect(jsonPath("$[0].documentId").value(downloaded.documentId().toString()))
                .andExpect(jsonPath("$[0].relationshipType").value("LINKED_REPORT"));

        // Requesting again is idempotent.
        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", first, link.id())).andExpect(status().isOk());

        // A second email referencing the same report reuses the stored document.
        String second = importEml(newsletter("second", "/reports/20261028_IMP_Q3.pdf"), "second.eml");
        SourceLinkView secondLink = linkByText(second, "IMP - Q3 Earnings Update");
        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", second, secondLink.id())).andExpect(status().isAccepted());
        assertThat(awaitStatus(second, secondLink.id(), LinkStatus.DOWNLOADED).documentId()).isEqualTo(downloaded.documentId());
        assertThat(jdbc.queryForObject("select count(*) from document", Integer.class)).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails")
                        .param("documentId", downloaded.documentId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2));

        // Deleting an email never deletes a shared report.
        String firstFile = jdbc.queryForObject("select storage_key from source_messages where id = ?::uuid", String.class, first);
        mvc.perform(delete("/api/v1/emails/{id}", first)).andExpect(status().isNoContent());
        assertThat(STORAGE.resolve("emails").resolve(firstFile)).doesNotExist();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/documents/{id}", downloaded.documentId()))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/documents", second))
                .andExpect(jsonPath("$.length()").value(1));

        // Deleting the document makes the link downloadable again and removes the association.
        mvc.perform(delete("/api/v1/documents/{id}", downloaded.documentId())).andExpect(status().isNoContent());
        assertThat(research.link(UUID.fromString(second), secondLink.id()).status()).isEqualTo(LinkStatus.READY);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/documents", second))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void failedDownloadsAreRecordedAndCanBeRetried() throws Exception {
        brokerage.stubFor(get(urlEqualTo("/reports/MWG_gone.pdf")).willReturn(aResponse().withStatus(404)));
        brokerage.stubFor(get(urlEqualTo("/reports/VCB_flaky.pdf")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        brokerage.stubFor(get(urlEqualTo("/reports/VCB_flaky.pdf")).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withBody(TestPdfs.pdf("vcb-" + UUID.randomUUID()))));

        String id = importEml(TestEmails.builder().subject("Retry test").html("""
                <p><strong>1. Báo cáo phân tích mới</strong></p>
                <p><a href="%s">MWG - Cập nhật KQKD</a></p>
                <p><a href="%s">VCB - Cập nhật KQKD</a></p>""".formatted(brokerageUrl("/reports/MWG_gone.pdf"), brokerageUrl("/reports/VCB_flaky.pdf")))
                .build(), "retry.eml");

        // Expired link: permanent failure, recorded on the link.
        SourceLinkView gone = linkByText(id, "MWG - Cập nhật KQKD");
        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", id, gone.id())).andExpect(status().isAccepted());
        SourceLinkView failed = awaitStatus(id, gone.id(), LinkStatus.FAILED);
        assertThat(failed.errorMessage()).contains("expired");
        assertThat(failed.download().attempts()).isEqualTo(1);

        // Transient failure: retried automatically with backoff, then succeeds.
        SourceLinkView flaky = linkByText(id, "VCB - Cập nhật KQKD");
        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", id, flaky.id())).andExpect(status().isAccepted());
        assertThat(awaitStatus(id, flaky.id(), LinkStatus.DOWNLOADED).download().attempts()).isEqualTo(2);

        // Manual retry of the failed link once the report is back.
        brokerage.stubFor(get(urlEqualTo("/reports/MWG_gone.pdf")).willReturn(aResponse().withBody(TestPdfs.pdf("mwg-" + UUID.randomUUID()))));
        mvc.perform(post("/api/v1/emails/{id}/links/{linkId}/download", id, gone.id())).andExpect(status().isAccepted());
        awaitStatus(id, gone.id(), LinkStatus.DOWNLOADED);
    }

    @Test
    void pdfAttachmentIsImportedIntoLibraryAndLinked() throws Exception {
        byte[] pdf = TestPdfs.pdf("attachment-" + UUID.randomUUID());
        String id = importEml(TestEmails.builder().text("Báo cáo đính kèm").attachment("VNM_Q3.pdf", "application/pdf", pdf)
                .attachment("logo.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}).build(), "attachment.eml");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}", id))
                .andExpect(jsonPath("$.attachments.length()").value(2))
                .andExpect(jsonPath("$.attachments[0].documentId").isNotEmpty())
                .andExpect(jsonPath("$.attachments[1].documentId").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/{id}/documents", id))
                .andExpect(jsonPath("$[0].relationshipType").value("ATTACHMENT"))
                .andExpect(jsonPath("$[0].originalFilename").value("VNM_Q3.pdf"));
    }

    @Test
    void rejectsMalformedAndUnsupportedFilesWithoutStoringThem() throws Exception {
        mvc.perform(multipart("/api/v1/emails/import").file(file("not an email at all".getBytes(), "junk.eml")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
        mvc.perform(multipart("/api/v1/emails/import").file(new MockMultipartFile("file", "report.pdf", "application/pdf", TestPdfs.pdf("x"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from source_messages", Integer.class)).isZero();
    }

    @Test
    void searchAndSenderFilter() throws Exception {
        importEml(TestEmails.builder().subject("Bản tin sáng 29/10").from("SSI Research <research@ssi.com.vn>").text("a").build(), "a.eml");
        importEml(TestEmails.builder().subject("Cơ hội đầu tư 29/10").text("b").build(), "b.eml");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails").param("q", "BẢN TIN"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].brokerage").value("SSI"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails").param("sender", "research@tcbs.com.vn"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/emails/senders"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private byte[] newsletter(String marker, String reportPath) {
        return TestEmails.builder().subject("Cơ hội đầu tư | " + marker).html("""
                <p><strong>1. Latest Research Publication</strong></p>
                <p><a href="%s"><strong>IMP - Q3 Earnings Update</strong></a></p>
                <p><strong>4. Promotions</strong></p>
                <p><a href="%s">Margin offer</a></p>
                <!-- %s -->""".formatted(brokerageUrl(reportPath), brokerageUrl("/promo"), marker)).build();
    }

    private String importEml(byte[] raw, String name) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/emails/import").file(file(raw, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private SourceLinkView linkByText(String emailId, String text) {
        List<SourceLinkView> links = research.links(UUID.fromString(emailId));
        return links.stream().filter(l -> text.equals(l.displayText())).findFirst().orElseThrow();
    }

    private SourceLinkView awaitStatus(String emailId, UUID linkId, LinkStatus expected) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> research.link(UUID.fromString(emailId), linkId), view -> view.status() == expected);
    }

    private static MockMultipartFile file(byte[] content, String name) {
        return new MockMultipartFile("file", name, "message/rfc822", content);
    }

    private static String brokerageUrl(String path) {
        return "http://localhost:" + brokerage.getPort() + path;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-email-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

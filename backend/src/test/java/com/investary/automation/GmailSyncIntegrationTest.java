package com.investary.automation;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.investary.TestDatabase;
import com.investary.email.TestEmails;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gmail OAuth and sync end to end: real PostgreSQL (Flyway V6), the real Phase 2 import, and Google's OAuth
 * and Gmail endpoints simulated by WireMock. No real Google account, credentials or emails are involved.
 */
@SpringBootTest(properties = {
        "investary.gmail.client-id=test-client.apps.googleusercontent.com",
        "investary.gmail.client-secret=test-secret",
        "investary.gmail.token-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "investary.gmail.max-retries=2",
        "investary.gmail.retry-backoff=10ms",
        "investary.gmail.max-retry-wait=50ms",
        "investary.gmail.sync.page-size=2",
        "investary.gmail.sync.max-messages-per-run=3",
        "investary.gmail.sync.initial-lookback-days=30",
        "investary.gmail.sync.recovery-max-lookback-days=30",
        "investary.gmail.sync.scheduler-enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class GmailSyncIntegrationTest {

    static final String API = "/gmail/v1/users/me";
    static final String REFRESH_TOKEN = "1//synthetic-refresh-token";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @RegisterExtension
    static WireMockExtension google = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final Path STORAGE = createTempDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("investary.storage.root", () -> STORAGE.resolve("documents").toString());
        registry.add("investary.email.storage.root", () -> STORAGE.resolve("emails").toString());
        registry.add("investary.download.temp-dir", () -> STORAGE.resolve("downloads").toString());
        registry.add("investary.gmail.auth-uri", () -> google.baseUrl() + "/o/oauth2/v2/auth");
        registry.add("investary.gmail.token-uri", () -> google.baseUrl() + "/token");
        registry.add("investary.gmail.revoke-uri", () -> google.baseUrl() + "/revoke");
        registry.add("investary.gmail.api-base-url", () -> google.baseUrl());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jdbc.update("delete from gmail_messages");
        jdbc.update("delete from gmail_sync_runs");
        jdbc.update("delete from gmail_accounts");
        jdbc.update("delete from email_source_rules");
        jdbc.update("delete from source_messages");
        jdbc.update("delete from document");
        google.resetAll();
    }

    // --- OAuth ---------------------------------------------------------------------------------------

    @Test
    void connectStoresOnlyAnEncryptedRefreshTokenAndRejectsForgedCallbacks() throws Exception {
        assertThat(connect("investor@example.com", "1000")).isEqualTo("connected");

        Map<String, Object> account = jdbc.queryForMap("select * from gmail_accounts");
        assertThat(account.get("status")).isEqualTo("CONNECTED");
        assertThat(new String((byte[]) account.get("refresh_token_ciphertext"), StandardCharsets.ISO_8859_1)).doesNotContain(REFRESH_TOKEN);
        String status = mvc.perform(get("/api/v1/integrations/gmail/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.account.emailAddress").value("investor@example.com"))
                .andExpect(jsonPath("$.account.status").value("CONNECTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(status).doesNotContain(REFRESH_TOKEN, "access-1", "refresh_token_ciphertext");
        google.verify(postRequestedFor(urlPathEqualTo("/token")).withRequestBody(containing("code_verifier=")));

        // Forged state, a state from another browser, and replay are all refused.
        assertThat(callback("code", "forged-state", null, null)).isEqualTo("error&reason=INVALID_STATE");
        Started started = startConnect();
        assertThat(callback("code", started.state(), null, new Cookie("investary_gmail_oauth", "other-browser")))
                .isEqualTo("error&reason=INVALID_STATE");
        assertThat(callback("code", started.state(), null, started.cookie())).as("state already burnt").isEqualTo("error&reason=INVALID_STATE");

        Started denied = startConnect();
        assertThat(callback(null, denied.state(), "access_denied", denied.cookie())).isEqualTo("error&reason=ACCESS_DENIED");
    }

    @Test
    void connectRefusesMissingScopeAndASecondAccount() throws Exception {
        google.stubFor(WireMock.post("/token").willReturn(okJson("""
                {"access_token":"access-1","expires_in":3600,"refresh_token":"%s","scope":"openid"}""".formatted(REFRESH_TOKEN))));
        google.stubFor(WireMock.post("/revoke").willReturn(aResponse().withStatus(200)));
        Started started = startConnect();
        assertThat(callback("code", started.state(), null, started.cookie())).isEqualTo("error&reason=SCOPE_NOT_GRANTED");
        assertThat(jdbc.queryForObject("select count(*) from gmail_accounts", Long.class)).isZero();

        assertThat(connect("investor@example.com", "1000")).isEqualTo("connected");
        assertThat(connect("someone-else@example.com", "1000")).isEqualTo("error&reason=ACCOUNT_LIMIT");
    }

    // --- Sync ----------------------------------------------------------------------------------------

    @Test
    void initialThenIncrementalSyncImportsOnlyApprovedSendersOnce() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        byte[] m1 = email("research@tcbs.com.vn", "Bản tin sáng");
        byte[] spoof = email("TCBS Research <attacker@evil.example>", "Bản tin sáng");
        byte[] m3 = email("research@tcbs.com.vn", "Spoofed");
        byte[] m4 = email("news@mail.tcbs.com.vn", "Bản tin chiều");
        stubMessage("m1", m1, "TCBS <research@tcbs.com.vn>", null);
        stubMessage("spoof", spoof, "TCBS Research <attacker@evil.example>", null);
        stubMessage("m3", m3, "research@tcbs.com.vn", "mx.google.com; dkim=fail; spf=fail; dmarc=fail (p=REJECT) header.from=tcbs.com.vn");
        stubMessage("m4", m4, "news@mail.tcbs.com.vn", "mx.google.com; dkim=pass; spf=pass; dmarc=pass header.from=tcbs.com.vn");
        stubList("from:(tcbs.com.vn)", null, List.of("m1", "spoof"), "page-2");
        stubList("from:(tcbs.com.vn)", "page-2", List.of("m3", "m4"), null);
        // The access token from the code exchange is rejected once: the client refreshes and continues.
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/profile")).withHeader("Authorization", equalTo("Bearer access-1"))
                .willReturn(aResponse().withStatus(401)));
        google.stubFor(WireMock.post("/token").withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(okJson("{\"access_token\":\"access-2\",\"expires_in\":3600,\"scope\":\"" + GmailProperties.SCOPE + "\"}")));
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/profile")).withHeader("Authorization", equalTo("Bearer access-2"))
                .willReturn(okJson("{\"emailAddress\":\"investor@example.com\",\"historyId\":\"2000\"}")));

        JsonNode initial = syncAndWait();

        assertThat(initial.path("mode").asString()).isEqualTo("INITIAL");
        assertThat(initial.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(initial.path("listed").asInt()).isEqualTo(4);
        assertThat(initial.path("imported").asInt()).isEqualTo(2);
        assertThat(initial.path("rejected").asInt()).isEqualTo(1);
        assertThat(initial.path("skipped").asInt()).isEqualTo(1);
        assertThat(outcomes()).containsExactlyInAnyOrderEntriesOf(Map.of("m1", "IMPORTED", "m3", "REJECTED", "m4", "IMPORTED"));
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class)).isEqualTo("2000");
        assertThat(jdbc.queryForObject("select count(*) from source_messages where source_type = 'GMAIL'", Long.class)).isEqualTo(2);
        String query = google.findAll(getRequestedFor(urlPathEqualTo(API + "/messages"))).getFirst().queryParameter("q").firstValue();
        long after = Long.parseLong(query.replaceAll(".*after:(\\d+).*", "$1"));
        assertThat(after).isBetween(Instant.now().minus(Duration.ofDays(30)).getEpochSecond() - 60,
                Instant.now().minus(Duration.ofDays(30)).getEpochSecond() + 60);

        // The stored original is exactly Gmail's raw message (no reconstruction).
        UUID m1Email = jdbc.queryForObject("select source_message_id from gmail_messages where gmail_message_id = 'm1'", UUID.class);
        byte[] original = mvc.perform(get("/api/v1/emails/{id}/original", m1Email)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(original).isEqualTo(m1);
        mvc.perform(get("/api/v1/emails/{id}", m1Email)).andExpect(jsonPath("$.senderAddress").value("research@tcbs.com.vn"));

        // Incremental: m1 appears again (already handled) next to a new message.
        byte[] m5 = email("research@tcbs.com.vn", "Bản tin tối");
        stubMessage("m5", m5, "research@tcbs.com.vn", null);
        stubHistory("2000", null, "{\"id\":\"2001\",\"messagesAdded\":[{\"message\":{\"id\":\"m1\"}}]},"
                + "{\"id\":\"2002\",\"messagesAdded\":[{\"message\":{\"id\":\"m5\"}}]}", "2010", null);

        JsonNode incremental = syncAndWait();

        assertThat(incremental.path("mode").asString()).isEqualTo("INCREMENTAL");
        assertThat(incremental.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(incremental.path("imported").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class)).isEqualTo("2010");
        google.verify(1, getRequestedFor(urlPathEqualTo(API + "/messages/m1")).withQueryParam("format", equalTo("raw")));
        assertThat(jdbc.queryForObject("select count(*) from source_messages where source_type = 'GMAIL'", Long.class)).isEqualTo(3);

        mvc.perform(get("/api/v1/integrations/gmail/sync-history")).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].mode").value("INCREMENTAL"));
        mvc.perform(get("/api/v1/integrations/gmail/status")).andExpect(jsonPath("$.importedTotal").value(3))
                .andExpect(jsonPath("$.account.initialSyncCompleted").value(true))
                .andExpect(jsonPath("$.lastRun.status").value("SUCCEEDED"));
    }

    @Test
    void aMessageImportedByHandIsMappedAsDuplicate() throws Exception {
        createRule("{\"name\":\"SSI\",\"senderAddresses\":[\"research@ssi.com.vn\"],\"autoPdfDownload\":false}");
        byte[] raw = email("research@ssi.com.vn", "Daily");
        String manual = mvc.perform(multipart("/api/v1/emails/import")
                        .file(new MockMultipartFile("file", "daily.eml", "message/rfc822", raw)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String manualId = json.readTree(manual).path("id").asString();
        connect("investor@example.com", "1000");
        stubProfile("3000");
        stubMessage("d1", raw, "research@ssi.com.vn", null);
        stubList("from:(research@ssi.com.vn)", null, List.of("d1"), null);

        JsonNode run = syncAndWait();

        assertThat(run.path("duplicates").asInt()).isEqualTo(1);
        assertThat(run.path("imported").asInt()).isZero();
        assertThat(jdbc.queryForObject("select source_message_id::text from gmail_messages where gmail_message_id = 'd1'", String.class))
                .isEqualTo(manualId);
        assertThat(jdbc.queryForObject("select count(*) from source_messages", Long.class)).isEqualTo(1);
    }

    @Test
    void rulesWithoutAutoImportAreRecordedButNotImported() throws Exception {
        createRule("{\"name\":\"VCBS\",\"senderDomains\":[\"vcbs.com.vn\"],\"autoImport\":false}");
        connect("investor@example.com", "1000");
        stubProfile("3000");
        stubMessage("v1", email("a@vcbs.com.vn", "x"), "a@vcbs.com.vn", null);
        stubList("from:(vcbs.com.vn)", null, List.of("v1"), null);

        JsonNode run = syncAndWait();

        assertThat(run.path("notImported").asInt()).isEqualTo(1);
        assertThat(outcomes()).containsEntry("v1", "NOT_IMPORTED");
        assertThat(jdbc.queryForObject("select count(*) from source_messages", Long.class)).isZero();
        google.verify(0, getRequestedFor(urlPathEqualTo(API + "/messages/v1")).withQueryParam("format", equalTo("raw")));
    }

    @Test
    void oversizedAndInvalidMessagesAreRejectedWithoutBreakingTheRun() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        stubProfile("3000");
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/messages/big")).withQueryParam("format", equalTo("metadata"))
                .willReturn(okJson(metadataJson("big", "research@tcbs.com.vn", "Huge", null, 26L * 1024 * 1024))));
        stubMessage("junk", "not an email at all".getBytes(StandardCharsets.UTF_8), "research@tcbs.com.vn", null);
        stubMessage("ok", email("research@tcbs.com.vn", "Fine"), "research@tcbs.com.vn", null);
        stubList("from:(tcbs.com.vn)", null, List.of("big", "junk", "ok"), null);

        JsonNode run = syncAndWait();

        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("rejected").asInt()).isEqualTo(2);
        assertThat(run.path("imported").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForList("select gmail_message_id || ':' || error_code from gmail_messages where status = 'REJECTED'", String.class))
                .containsExactlyInAnyOrder("big:EMAIL_TOO_LARGE", "junk:INVALID_EMAIL");
        google.verify(0, getRequestedFor(urlPathEqualTo(API + "/messages/big")).withQueryParam("format", equalTo("raw")));
    }

    @Test
    void theImportLimitStopsARunThatTheNextRunContinues() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        stubProfile("4000");
        for (String id : List.of("a1", "a2", "a3", "a4")) {
            stubMessage(id, email("research@tcbs.com.vn", "Report " + id), "research@tcbs.com.vn", null);
        }
        stubList("from:(tcbs.com.vn)", null, List.of("a1", "a2"), "p2");
        stubList("from:(tcbs.com.vn)", "p2", List.of("a3", "a4"), null);

        JsonNode first = syncAndWait();
        assertThat(first.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(first.path("imported").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class)).isNull();

        JsonNode second = syncAndWait();
        assertThat(second.path("mode").asString()).isEqualTo("INITIAL");
        assertThat(second.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(second.path("imported").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class)).isEqualTo("4000");
        assertThat(jdbc.queryForObject("select count(*) from source_messages", Long.class)).isEqualTo(4);
    }

    @Test
    void anExpiredCursorRecoversOverABoundedWindow() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        Instant lastSuccess = Instant.now().minus(Duration.ofDays(3));
        jdbc.update("update gmail_accounts set history_id = '10', initial_sync_completed_at = now(), last_sync_success_at = ?",
                Timestamp.from(lastSuccess));
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/history")).willReturn(aResponse().withStatus(404)));
        stubProfile("5000");
        stubMessage("r1", email("research@tcbs.com.vn", "Missed"), "research@tcbs.com.vn", null);
        stubList("from:(tcbs.com.vn)", null, List.of("r1"), null);

        JsonNode run = syncAndWait();

        assertThat(run.path("mode").asString()).isEqualTo("RECOVERY");
        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("imported").asInt()).isEqualTo(1);
        assertThat(run.path("warning").asString()).contains("history expired");
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class)).isEqualTo("5000");
        assertThat(lastAfter()).isBetween(lastSuccess.minus(Duration.ofDays(1)).getEpochSecond() - 5,
                lastSuccess.minus(Duration.ofDays(1)).getEpochSecond() + 5);

        // Long gone: the window is capped at recovery-max-lookback-days, never an unrestricted scan.
        jdbc.update("update gmail_accounts set history_id = '10', last_sync_success_at = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(400))));
        JsonNode capped = syncAndWait();
        assertThat(capped.path("mode").asString()).isEqualTo("RECOVERY");
        long floor = Instant.now().minus(Duration.ofDays(30)).getEpochSecond();
        assertThat(lastAfter()).isBetween(floor - 60, floor + 60);
        assertThat(capped.path("warning").asString()).contains("older than 30 days");
    }

    @Test
    void aTransientFailureKeepsTheCursorAndTheRetryImportsTheRestOnce() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        jdbc.update("update gmail_accounts set history_id = '100', initial_sync_completed_at = now(), last_sync_success_at = now()");
        stubMessage("t1", email("research@tcbs.com.vn", "One"), "research@tcbs.com.vn", null);
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/messages/t2")).withQueryParam("format", equalTo("metadata"))
                .willReturn(okJson(metadataJson("t2", "research@tcbs.com.vn", "Two", null, 100))));
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/messages/t2")).withQueryParam("format", equalTo("raw"))
                .willReturn(aResponse().withStatus(503)));
        stubHistory("100", null, "{\"id\":\"101\",\"messagesAdded\":[{\"message\":{\"id\":\"t1\"}}]}", null, "h2");
        stubHistory("100", "h2", "{\"id\":\"102\",\"messagesAdded\":[{\"message\":{\"id\":\"t2\"}}]}", "110", null);

        JsonNode failed = syncAndWait();

        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("GMAIL_UNAVAILABLE");
        assertThat(failed.path("imported").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class))
                .as("cursor only advanced past the fully handled first page").isEqualTo("101");
        assertThat(jdbc.queryForObject("select last_sync_error_code from gmail_accounts", String.class)).isEqualTo("GMAIL_UNAVAILABLE");

        stubMessage("t2", email("research@tcbs.com.vn", "Two"), "research@tcbs.com.vn", null);
        stubHistory("101", null, "{\"id\":\"102\",\"messagesAdded\":[{\"message\":{\"id\":\"t2\"}}]}", "110", null);

        JsonNode retried = syncAndWait();

        assertThat(retried.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(retried.path("imported").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select history_id from gmail_accounts", String.class)).isEqualTo("110");
        assertThat(jdbc.queryForObject("select count(*) from source_messages", Long.class)).isEqualTo(2);
        google.verify(1, getRequestedFor(urlPathEqualTo(API + "/messages/t1")).withQueryParam("format", equalTo("raw")));
    }

    @Test
    void onlyOneSyncRunsAndAnInterruptedRunIsClosed() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        UUID accountId = jdbc.queryForObject("select id from gmail_accounts", UUID.class);

        jdbc.update("update gmail_accounts set sync_lock_token = ?, sync_lock_until = now() + interval '5 minutes'", UUID.randomUUID());
        mvc.perform(post("/api/v1/integrations/gmail/sync"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GMAIL_SYNC_IN_PROGRESS"));

        // A crashed sync: its run is still RUNNING and its lease has expired.
        UUID crashed = UUID.randomUUID();
        jdbc.update("insert into gmail_sync_runs (id, account_id, trigger_type, status, started_at) values (?, ?, 'SCHEDULED', 'RUNNING', now() - interval '1 hour')",
                crashed, accountId);
        jdbc.update("update gmail_accounts set sync_lock_until = now() - interval '1 minute'");
        stubProfile("6000");
        stubList("from:(tcbs.com.vn)", null, List.of(), null);

        JsonNode run = syncAndWait();

        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select error_code from gmail_sync_runs where id = ?", String.class, crashed)).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("select sync_lock_token from gmail_accounts", UUID.class)).as("released").isNull();
    }

    @Test
    void aRevokedGrantRequiresReconnectingAndKeepsTheCursor() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        jdbc.update("update gmail_accounts set history_id = '700', initial_sync_completed_at = now()");
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/history")).willReturn(aResponse().withStatus(401)));
        google.stubFor(WireMock.post("/token").withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}")));

        JsonNode run = syncAndWait();

        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("errorCode").asString()).isEqualTo("RECONNECT_REQUIRED");
        Map<String, Object> account = jdbc.queryForMap("select status, refresh_token_ciphertext, history_id from gmail_accounts");
        assertThat(account.get("status")).isEqualTo("RECONNECT_REQUIRED");
        assertThat(account.get("refresh_token_ciphertext")).isNull();
        mvc.perform(post("/api/v1/integrations/gmail/sync"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GMAIL_NOT_CONNECTED"));

        google.resetAll();
        assertThat(connect("investor@example.com", "1000")).isEqualTo("connected");
        Map<String, Object> reconnected = jdbc.queryForMap("select status, history_id from gmail_accounts");
        assertThat(reconnected.get("status")).isEqualTo("CONNECTED");
        assertThat(reconnected.get("history_id")).isEqualTo("700");
    }

    @Test
    void disconnectRevokesAndForgetsTokensButKeepsImportedEmails() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}");
        connect("investor@example.com", "1000");
        stubProfile("8000");
        stubMessage("k1", email("research@tcbs.com.vn", "Keep"), "research@tcbs.com.vn", null);
        stubList("from:(tcbs.com.vn)", null, List.of("k1"), null);
        syncAndWait();
        google.stubFor(WireMock.post("/revoke").willReturn(aResponse().withStatus(200)));

        mvc.perform(post("/api/v1/integrations/gmail/disconnect")).andExpect(status().isNoContent());

        google.verify(postRequestedFor(urlPathEqualTo("/revoke")).withRequestBody(equalTo("token=" + java.net.URLEncoder.encode(REFRESH_TOKEN, StandardCharsets.UTF_8))));
        Map<String, Object> account = jdbc.queryForMap("select status, refresh_token_ciphertext, history_id from gmail_accounts");
        assertThat(account.get("status")).isEqualTo("DISCONNECTED");
        assertThat(account.get("refresh_token_ciphertext")).isNull();
        assertThat(account.get("history_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from source_messages", Long.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/integrations/gmail/status")).andExpect(jsonPath("$.account").doesNotExist());
        mvc.perform(post("/api/v1/integrations/gmail/sync")).andExpect(status().isConflict());
    }

    @Test
    void sourceRulesAreValidatedAndUnique() throws Exception {
        createRule("{\"name\":\"TCBS\",\"senderDomains\":[\"TCBS.com.vn\"],\"subjectKeywords\":[\"Bản tin\"]}");
        mvc.perform(post("/api/v1/automation/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tcbs\",\"senderDomains\":[\"tcbs.com.vn\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SOURCE_RULE"));
        mvc.perform(post("/api/v1/automation/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"senderDomains\":[\"x.vn\"],\"gmailQuery\":\"in:anywhere\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.gmailQuery").exists());
        mvc.perform(get("/api/v1/automation/sources"))
                .andExpect(jsonPath("$[0].senderDomains[0]").value("tcbs.com.vn"))
                .andExpect(jsonPath("$[0].autoImport").value(true))
                .andExpect(jsonPath("$[0].autoPdfDownload").value(false));
    }

    @Test
    void statusNeverExposesCredentials() throws Exception {
        // Credentials are configured for this context; the status still never exposes them.
        mvc.perform(get("/api/v1/integrations/gmail/status"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    // --- helpers -------------------------------------------------------------------------------------

    record Started(String state, Cookie cookie) {
    }

    Started startConnect() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/integrations/gmail/connect")).andExpect(status().isOk()).andReturn();
        String url = json.readTree(result.getResponse().getContentAsString()).path("authorizationUrl").asString();
        assertThat(url).startsWith(google.baseUrl() + "/o/oauth2/v2/auth").contains("code_challenge=");
        String state = UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams().getFirst("state");
        return new Started(java.net.URLDecoder.decode(state, StandardCharsets.UTF_8), result.getResponse().getCookie("investary_gmail_oauth"));
    }

    /** Full connect through the callback; returns the UI result ("connected" or "error&reason=…"). */
    String connect(String email, String historyId) throws Exception {
        google.stubFor(WireMock.post("/token").withRequestBody(containing("grant_type=authorization_code")).willReturn(okJson("""
                {"access_token":"access-1","expires_in":3600,"refresh_token":"%s","scope":"%s","token_type":"Bearer"}"""
                .formatted(REFRESH_TOKEN, GmailProperties.SCOPE))));
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/profile")).withHeader("Authorization", equalTo("Bearer access-1"))
                .willReturn(okJson("{\"emailAddress\":\"" + email + "\",\"historyId\":\"" + historyId + "\"}")));
        google.stubFor(WireMock.post("/revoke").willReturn(aResponse().withStatus(200)));
        Started started = startConnect();
        return callback("auth-code", started.state(), null, started.cookie());
    }

    String callback(String code, String state, String error, Cookie cookie) throws Exception {
        var request = get("/api/v1/integrations/gmail/callback");
        if (code != null) {
            request.param("code", code);
        }
        if (state != null) {
            request.param("state", state);
        }
        if (error != null) {
            request.param("error", error);
        }
        if (cookie != null) {
            request.cookie(cookie);
        }
        String location = mvc.perform(request).andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("http://localhost:4200/automation?gmail=");
        return location.substring("http://localhost:4200/automation?gmail=".length());
    }

    void createRule(String body) throws Exception {
        mvc.perform(post("/api/v1/automation/sources").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    JsonNode syncAndWait() throws Exception {
        String body = mvc.perform(post("/api/v1/integrations/gmail/sync")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(body).path("runId").asString();
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> {
            String run = mvc.perform(get("/api/v1/integrations/gmail/sync-history/{id}", runId)).andReturn().getResponse().getContentAsString();
            return json.readTree(run);
        }, run -> !"RUNNING".equals(run.path("status").asString())
                && jdbc.queryForObject("select sync_lock_token is null from gmail_accounts where status <> 'DISCONNECTED' limit 1", Boolean.class));
    }

    Map<String, String> outcomes() {
        return jdbc.queryForList("select gmail_message_id, status from gmail_messages").stream()
                .collect(Collectors.toMap(r -> (String) r.get("gmail_message_id"), r -> (String) r.get("status")));
    }

    long lastAfter() {
        var requests = google.findAll(getRequestedFor(urlPathEqualTo(API + "/messages")));
        String query = requests.getLast().queryParameter("q").firstValue();
        return Long.parseLong(query.replaceAll(".*after:(\\d+).*", "$1"));
    }

    static byte[] email(String from, String subject) {
        return TestEmails.builder().from(from).subject(subject)
                .text("Synthetic research update " + UUID.randomUUID() + "\nVN-Index: tăng 5 điểm.").build();
    }

    void stubProfile(String historyId) {
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/profile"))
                .willReturn(okJson("{\"emailAddress\":\"investor@example.com\",\"historyId\":\"" + historyId + "\"}")));
    }

    void stubList(String queryPart, String pageToken, List<String> ids, String nextPageToken) {
        String messages = ids.stream().map(id -> "{\"id\":\"" + id + "\",\"threadId\":\"t-" + id + "\"}").collect(Collectors.joining(","));
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/messages"))
                .withQueryParam("q", containing(queryPart))
                .withQueryParam("pageToken", pageToken == null ? absent() : equalTo(pageToken))
                .willReturn(okJson("{\"messages\":[" + messages + "]" + (nextPageToken == null ? "" : ",\"nextPageToken\":\"" + nextPageToken + "\"")
                        + ",\"resultSizeEstimate\":" + ids.size() + "}")));
    }

    void stubHistory(String start, String pageToken, String records, String historyId, String nextPageToken) {
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/history"))
                .withQueryParam("startHistoryId", equalTo(start))
                .withQueryParam("pageToken", pageToken == null ? absent() : equalTo(pageToken))
                .willReturn(okJson("{\"history\":[" + records + "]"
                        + (historyId == null ? "" : ",\"historyId\":\"" + historyId + "\"")
                        + (nextPageToken == null ? "" : ",\"nextPageToken\":\"" + nextPageToken + "\"") + "}")));
    }

    void stubMessage(String id, byte[] raw, String from, String authenticationResults) {
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/messages/" + id)).withQueryParam("format", equalTo("metadata"))
                .willReturn(okJson(metadataJson(id, from, "Subject " + id, authenticationResults, raw.length))));
        google.stubFor(WireMock.get(urlPathEqualTo(API + "/messages/" + id)).withQueryParam("format", equalTo("raw"))
                .willReturn(okJson("{\"id\":\"" + id + "\",\"threadId\":\"t-" + id + "\",\"raw\":\""
                        + Base64.getUrlEncoder().encodeToString(raw) + "\"}")));
    }

    String metadataJson(String id, String from, String subject, String authenticationResults, long size) {
        var headers = json.createArrayNode();
        if (authenticationResults != null) {
            headers.addObject().put("name", "Authentication-Results").put("value", authenticationResults);
        }
        headers.addObject().put("name", "From").put("value", from);
        headers.addObject().put("name", "Subject").put("value", subject);
        var node = json.createObjectNode();
        node.put("id", id).put("threadId", "t-" + id).put("historyId", "1").put("internalDate", "1790000000000")
                .put("sizeEstimate", size);
        node.putArray("labelIds").add("INBOX");
        node.putObject("payload").set("headers", headers);
        return json.writeValueAsString(node);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("investary-gmail-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

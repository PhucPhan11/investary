package com.investary.automation.google;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.investary.automation.GmailProperties;
import com.investary.automation.google.GmailApiClient.TokenSource;
import com.investary.automation.google.GoogleExceptions.GoogleApiException;
import com.investary.automation.google.GoogleExceptions.GoogleTransientException;
import com.investary.automation.google.GoogleExceptions.HistoryExpiredException;
import com.investary.automation.google.GoogleExceptions.InvalidGrantException;
import com.investary.automation.google.GoogleOAuthClient.Tokens;

import tools.jackson.databind.json.JsonMapper;

/**
 * Google OAuth and Gmail API clients against WireMock (no real Google endpoints, no real credentials).
 */
class GoogleClientsTest {

    @RegisterExtension
    static WireMockExtension google = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    GoogleOAuthClient oauth;
    GmailApiClient gmail;
    final List<Boolean> tokenRequests = new ArrayList<>();
    final TokenSource tokens = force -> {
        tokenRequests.add(force);
        return force ? "fresh-token" : "old-token";
    };

    @BeforeEach
    void setUp() {
        String base = google.baseUrl();
        GmailProperties properties = new GmailProperties("client-id", "client-secret", "key", base + "/callback", null,
                base + "/auth", base + "/token", base + "/revoke", base, Duration.ofSeconds(2), Duration.ofSeconds(2), 2,
                Duration.ofMillis(10), Duration.ofMillis(50), null,
                new GmailProperties.Sync(false, null, 30, 200, 30, null, 2));
        GoogleHttp http = new GoogleHttp(properties, JsonMapper.builder().build());
        oauth = new GoogleOAuthClient(properties, http);
        gmail = new GmailApiClient(properties, http);
    }

    @Test
    void authorizationUrlRequestsOnlyReadOnlyOfflineAccessWithPkce() {
        String url = oauth.authorizationUrl("st+ate/1", "challenge");
        assertThat(url).startsWith(google.baseUrl() + "/auth?")
                .contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly")
                .contains("access_type=offline", "prompt=consent", "response_type=code", "code_challenge_method=S256",
                        "code_challenge=challenge", "state=st%2Bate%2F1", "include_granted_scopes=false")
                .doesNotContain("client-secret");
    }

    @Test
    void exchangesTheCodeWithTheVerifier() {
        google.stubFor(post("/token").willReturn(okJson("""
                {"access_token":"at","expires_in":3599,"refresh_token":"rt","scope":"https://www.googleapis.com/auth/gmail.readonly","token_type":"Bearer"}""")));

        Tokens tokens = oauth.exchangeCode("auth-code", "verifier-123");

        assertThat(tokens.accessToken()).isEqualTo("at");
        assertThat(tokens.refreshToken()).isEqualTo("rt");
        assertThat(tokens.grants(GmailProperties.SCOPE)).isTrue();
        assertThat(tokens.toString()).doesNotContain("at\"", "rt");
        google.verify(postRequestedFor(urlPathEqualTo("/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=auth-code"))
                .withRequestBody(containing("code_verifier=verifier-123"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded")));
    }

    @Test
    void refreshClassifiesInvalidGrantAndOutages() {
        google.stubFor(post("/token").inScenario("refresh").whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"access_token\":\"new\",\"expires_in\":3600,\"scope\":\"" + GmailProperties.SCOPE + "\"}"))
                .willSetStateTo("revoked"));
        google.stubFor(post("/token").inScenario("refresh").whenScenarioStateIs("revoked")
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"))
                .willSetStateTo("down"));
        google.stubFor(post("/token").inScenario("refresh").whenScenarioStateIs("down")
                .willReturn(aResponse().withStatus(503)));

        assertThat(oauth.refresh("rt").accessToken()).isEqualTo("new");
        assertThatThrownBy(() -> oauth.refresh("rt")).isInstanceOf(InvalidGrantException.class);
        assertThatThrownBy(() -> oauth.refresh("rt")).isInstanceOf(GoogleTransientException.class);
        google.verify(postRequestedFor(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=refresh_token")));
    }

    @Test
    void rejectedExchangeDoesNotEchoGoogleText() {
        google.stubFor(post("/token").willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"redirect_uri_mismatch\",\"error_description\":\"<script>x</script>\"}")));
        assertThatThrownBy(() -> oauth.exchangeCode("c", "v"))
                .isInstanceOf(GoogleApiException.class)
                .hasMessageNotContaining("<script>");
    }

    @Test
    void revokesWithAFormPost() {
        google.stubFor(post("/revoke").willReturn(aResponse().withStatus(200)));
        assertThat(oauth.revoke("rt")).isTrue();
        google.verify(postRequestedFor(urlPathEqualTo("/revoke")).withRequestBody(equalTo("token=rt")));

        google.stubFor(post("/revoke").willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_token\"}")));
        assertThat(oauth.revoke("gone")).isFalse();
    }

    @Test
    void listsMessagesPageByPageWithTheEncodedQuery() {
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages")).withQueryParam("pageToken", equalTo("p2"))
                .willReturn(okJson("{\"messages\":[{\"id\":\"m3\",\"threadId\":\"t3\"}]}")));
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages")).withQueryParam("q", equalTo("from:(a+b@x.vn) after:1"))
                .withQueryParam("pageToken", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(okJson("{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"},{\"id\":\"m2\",\"threadId\":\"t1\"}],\"nextPageToken\":\"p2\"}")));

        var first = gmail.listMessages(tokens, "from:(a+b@x.vn) after:1", null);
        var second = gmail.listMessages(tokens, "from:(a+b@x.vn) after:1", first.nextPageToken());

        assertThat(first.messages()).extracting(GmailApiClient.MessageRef::id).containsExactly("m1", "m2");
        assertThat(second.messages()).extracting(GmailApiClient.MessageRef::id).containsExactly("m3");
        assertThat(second.nextPageToken()).isNull();
        google.verify(getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/messages"))
                .withHeader("Authorization", equalTo("Bearer old-token")).withQueryParam("maxResults", equalTo("2")));
    }

    @Test
    void refreshesOnceAfter401() {
        google.stubFor(get("/gmail/v1/users/me/profile").withHeader("Authorization", equalTo("Bearer old-token"))
                .willReturn(aResponse().withStatus(401)));
        google.stubFor(get("/gmail/v1/users/me/profile").withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(okJson("{\"emailAddress\":\"investor@example.com\",\"historyId\":\"12345\"}")));

        var profile = gmail.profile(tokens);

        assertThat(profile.emailAddress()).isEqualTo("investor@example.com");
        assertThat(profile.historyId()).isEqualTo("12345");
        assertThat(tokenRequests).containsExactly(false, true);
    }

    @Test
    void retriesRateLimitsThenGivesUp() {
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/m1")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("rate"));
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/m1")).inScenario("flaky").whenScenarioStateIs("rate")
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"))
                .willSetStateTo("ok"));
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/m1")).inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"id\":\"m1\",\"raw\":\"" + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("Subject: hi\r\n\r\nbody ÿ?".getBytes(StandardCharsets.UTF_8)) + "\"}")));

        assertThat(new String(gmail.raw(tokens, "m1"), StandardCharsets.UTF_8)).isEqualTo("Subject: hi\r\n\r\nbody ÿ?");

        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/m2")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> gmail.raw(tokens, "m2"))
                .isInstanceOfSatisfying(GoogleTransientException.class, e -> assertThat(e.code()).isEqualTo("GMAIL_UNAVAILABLE"));
        google.verify(3, getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/messages/m2")));
    }

    @Test
    void retryAfterIsCapped() {
        assertThat(gmail.backoff(0, "3600")).isEqualTo(Duration.ofMillis(50));
        assertThat(gmail.backoff(1, null)).isEqualTo(Duration.ofMillis(20));
    }

    @Test
    void readsHistoryAndReportsAnExpiredCursor() {
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/history")).withQueryParam("startHistoryId", equalTo("100"))
                .willReturn(okJson("""
                        {"history":[{"id":"101","messagesAdded":[{"message":{"id":"m1","threadId":"t1","labelIds":["INBOX"]}}]},
                                    {"id":"102","messages":[{"id":"m0"}]}],
                         "historyId":"105"}""")));
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/history")).withQueryParam("startHistoryId", equalTo("1"))
                .willReturn(aResponse().withStatus(404)));

        var page = gmail.listHistory(tokens, "100", null);
        assertThat(page.history()).hasSize(2);
        assertThat(page.history().getFirst().messagesAdded()).extracting(GmailApiClient.MessageRef::id).containsExactly("m1");
        assertThat(page.history().get(1).messagesAdded()).isEmpty();
        assertThat(page.historyId()).isEqualTo("105");
        google.verify(getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/history")).withQueryParam("historyTypes", equalTo("messageAdded")));

        assertThatThrownBy(() -> gmail.listHistory(tokens, "1", null)).isInstanceOf(HistoryExpiredException.class);
    }

    @Test
    void readsMetadataHeaders() {
        google.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/m9")).withQueryParam("format", equalTo("metadata"))
                .willReturn(okJson("""
                        {"id":"m9","threadId":"t9","historyId":"77","internalDate":"1790000000000","sizeEstimate":1234,
                         "labelIds":["INBOX","CATEGORY_UPDATES"],
                         "payload":{"headers":[{"name":"From","value":"TCBS <research@tcbs.com.vn>"},{"name":"Subject","value":"Bản tin"}]}}""")));

        var metadata = gmail.metadata(tokens, "m9");

        assertThat(metadata.headers("from")).containsExactly("TCBS <research@tcbs.com.vn>");
        assertThat(metadata.sizeEstimate()).isEqualTo(1234);
        assertThat(metadata.historyId()).isEqualTo("77");
        assertThat(metadata.hasLabel("INBOX")).isTrue();
        google.verify(getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/messages/m9"))
                .withQueryParam("metadataHeaders", equalTo("Authentication-Results")));
    }
}

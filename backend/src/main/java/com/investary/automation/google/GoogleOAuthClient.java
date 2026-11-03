package com.investary.automation.google;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.investary.automation.GmailProperties;
import com.investary.automation.google.GoogleExceptions.GoogleApiException;
import com.investary.automation.google.GoogleExceptions.GoogleTransientException;
import com.investary.automation.google.GoogleExceptions.InvalidGrantException;
import com.investary.automation.google.GoogleHttp.Response;

/**
 * Google OAuth 2.0 for web server applications: authorization URL (with PKCE), code exchange, refresh
 * and revocation. Tokens are returned to the caller only; nothing here logs them.
 */
@Component
public class GoogleOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private final GmailProperties properties;
    private final GoogleHttp http;

    public GoogleOAuthClient(GmailProperties properties, GoogleHttp http) {
        this.properties = properties;
        this.http = http;
    }

    /**
     * Read-only Gmail scope only, offline access (refresh token), consent prompt (so Google issues a new
     * refresh token on reconnect), no incremental scopes, PKCE {@code S256}.
     */
    public String authorizationUrl(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.authUri())
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("response_type", "code")
                .queryParam("scope", "{scope}")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("include_granted_scopes", "false")
                .queryParam("state", "{state}")
                .queryParam("code_challenge", "{challenge}")
                .queryParam("code_challenge_method", "S256")
                .encode()
                .buildAndExpand(Map.of("clientId", properties.clientId(), "redirectUri", properties.redirectUri(),
                        "scope", GmailProperties.SCOPE, "state", state, "challenge", codeChallenge))
                .toUriString();
    }

    public Tokens exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", codeVerifier);
        form.put("redirect_uri", properties.redirectUri());
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        return tokens(post(form), "TOKEN_EXCHANGE_FAILED");
    }

    /**
     * @throws InvalidGrantException     if the refresh token was revoked or expired
     * @throws GoogleTransientException  if Google is unavailable
     */
    public Tokens refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        return tokens(post(form), "TOKEN_REFRESH_FAILED");
    }

    /**
     * Revokes the grant. Revoking the refresh token also invalidates its access tokens.
     *
     * @return true if Google confirmed the revocation (an already invalid token answers 400)
     */
    public boolean revoke(String token) {
        try {
            Response response = http.postForm(URI.create(properties.revokeUri()), encode(Map.of("token", token)));
            if (!response.ok()) {
                log.warn("Google token revocation answered status={}", response.status());
            }
            return response.ok();
        } catch (GoogleApiException e) {
            log.warn("Google token revocation failed code={}", e.code());
            return false;
        }
    }

    private Response post(Map<String, String> form) {
        return http.postForm(URI.create(properties.tokenUri()), encode(form));
    }

    private static Tokens tokens(Response response, String failureCode) {
        if (response.ok()) {
            String accessToken = response.body().path("access_token").asString(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new GoogleApiException(failureCode, "Google returned no access token");
            }
            return new Tokens(accessToken,
                    response.body().path("expires_in").asLong(3600),
                    response.body().path("refresh_token").asString(null),
                    response.body().path("scope").asString(""));
        }
        String reason = response.errorReason();
        if ("invalid_grant".equals(reason)) {
            throw new InvalidGrantException();
        }
        if (response.status() == 429 || response.status() >= 500) {
            throw new GoogleTransientException("GOOGLE_UNAVAILABLE", "Google's token endpoint is unavailable (status "
                    + response.status() + ")");
        }
        throw new GoogleApiException(failureCode, "Google rejected the token request (status " + response.status()
                + (reason.isBlank() ? "" : ", " + safe(reason)) + ")");
    }

    /** OAuth error codes are short identifiers; anything else is not echoed. */
    private static String safe(String reason) {
        return reason.matches("[a-z_]{1,40}") ? reason : "error";
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /**
     * @param refreshToken only present on a code exchange with {@code access_type=offline} (and sometimes on refresh)
     * @param scope        space-separated scopes the user actually granted
     */
    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken, String scope) {

        public boolean grants(String requiredScope) {
            return scope != null && java.util.Arrays.asList(scope.split(" ")).contains(requiredScope);
        }

        @Override
        public String toString() {
            return "Tokens[scope=" + scope + "]"; // never print token values
        }
    }
}

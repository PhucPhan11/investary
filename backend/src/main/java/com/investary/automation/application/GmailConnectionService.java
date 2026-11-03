package com.investary.automation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.investary.automation.GmailProperties;
import com.investary.automation.application.AutomationExceptions.GmailNotConfiguredException;
import com.investary.automation.google.GmailApiClient;
import com.investary.automation.google.GmailApiClient.Profile;
import com.investary.automation.google.GoogleExceptions.GoogleApiException;
import com.investary.automation.google.GoogleOAuthClient;
import com.investary.automation.google.GoogleOAuthClient.Tokens;
import com.investary.automation.persistence.GmailAccountStore;
import com.investary.automation.persistence.GmailAccountStore.AccountRow;
import com.investary.automation.security.OAuthStateStore;
import com.investary.automation.security.OAuthStateStore.Authorization;
import com.investary.automation.security.TokenCipher;
import com.investary.automation.security.TokenCipher.TokenUnreadableException;

/**
 * Connects, reconnects and disconnects the Gmail account (OAuth 2.0 authorization code flow with PKCE).
 * Tokens stay on the server: the browser only ever sees Google's consent page and fixed result codes.
 */
@Service
public class GmailConnectionService {

    private static final Logger log = LoggerFactory.getLogger(GmailConnectionService.class);

    private final GmailProperties properties;
    private final OAuthStateStore states;
    private final GoogleOAuthClient oauth;
    private final GmailApiClient gmail;
    private final TokenCipher cipher;
    private final GmailAccountStore accounts;
    private final GmailAccessTokens tokens;
    private final Clock clock;

    public GmailConnectionService(GmailProperties properties, OAuthStateStore states, GoogleOAuthClient oauth, GmailApiClient gmail,
                                  TokenCipher cipher, GmailAccountStore accounts, GmailAccessTokens tokens, Clock clock) {
        this.properties = properties;
        this.states = states;
        this.oauth = oauth;
        this.gmail = gmail;
        this.cipher = cipher;
        this.accounts = accounts;
        this.tokens = tokens;
        this.clock = clock;
    }

    public boolean configured() {
        return properties.credentialsConfigured() && cipher.isAvailable();
    }

    /**
     * Starts an authorization.
     *
     * @return the Google consent URL and the browser binding to put into an HttpOnly cookie
     */
    public PendingAuthorization connect() {
        if (!configured()) {
            throw new GmailNotConfiguredException();
        }
        Authorization authorization = states.start();
        return new PendingAuthorization(oauth.authorizationUrl(authorization.state(), authorization.codeChallenge()),
                authorization.binding());
    }

    /**
     * Completes an authorization from Google's redirect.
     *
     * @return empty on success, otherwise a fixed reason code for the UI
     */
    public Optional<String> complete(String code, String state, String error, String binding) {
        if (!configured()) {
            return Optional.of("NOT_CONFIGURED");
        }
        // Validate the state first in every case, so a state can never be reused.
        Optional<String> verifier = states.consume(state, binding);
        if (error != null && !error.isBlank()) {
            return Optional.of("access_denied".equals(error) ? "ACCESS_DENIED" : "GOOGLE_ERROR");
        }
        if (verifier.isEmpty()) {
            log.warn("Gmail authorization callback rejected: unknown, expired or foreign state");
            return Optional.of("INVALID_STATE");
        }
        if (code == null || code.isBlank() || code.length() > 2048) {
            return Optional.of("GOOGLE_ERROR");
        }
        Tokens granted;
        try {
            granted = oauth.exchangeCode(code, verifier.get());
        } catch (GoogleApiException e) {
            log.warn("Gmail authorization code exchange failed code={}", e.code());
            return Optional.of("TOKEN_EXCHANGE_FAILED");
        }
        if (!granted.grants(GmailProperties.SCOPE)) {
            oauth.revoke(granted.accessToken());
            return Optional.of("SCOPE_NOT_GRANTED");
        }
        if (granted.refreshToken() == null || granted.refreshToken().isBlank()) {
            return Optional.of("NO_REFRESH_TOKEN");
        }
        Profile profile;
        try {
            profile = gmail.profile(forceRefresh -> granted.accessToken());
        } catch (GoogleApiException e) {
            log.warn("Gmail profile could not be read after authorization code={}", e.code());
            return Optional.of("GOOGLE_ERROR");
        }
        String email = profile.emailAddress();
        if (email == null || email.isBlank() || email.length() > 320) {
            return Optional.of("GOOGLE_ERROR");
        }
        Optional<AccountRow> active = accounts.findActive();
        if (active.isPresent() && !active.get().emailAddress().equalsIgnoreCase(email)) {
            oauth.revoke(granted.refreshToken());
            return Optional.of("ACCOUNT_LIMIT");
        }
        Instant now = clock.instant();
        Optional<AccountRow> existing = accounts.findByEmail(email);
        UUID id = existing.map(AccountRow::id).orElseGet(UUID::randomUUID);
        byte[] ciphertext = cipher.encrypt(granted.refreshToken(), id);
        if (existing.isPresent()) {
            accounts.updateConnected(id, ciphertext, cipher.keyId(), granted.scope(), now);
        } else {
            accounts.insertConnected(id, email, ciphertext, cipher.keyId(), granted.scope(), now);
        }
        tokens.put(id, granted);
        log.info("Gmail account connected accountId={} reconnect={}", id, existing.isPresent());
        return Optional.empty();
    }

    /** Revokes the grant at Google (best effort) and forgets the tokens and the cursor. Idempotent. */
    public void disconnect() {
        Optional<AccountRow> active = accounts.findActive();
        if (active.isEmpty()) {
            return;
        }
        AccountRow account = active.get();
        if (account.refreshTokenCiphertext() != null && cipher.isAvailable()) {
            try {
                String refreshToken = cipher.decrypt(account.refreshTokenCiphertext(), account.tokenKeyId(), account.id());
                boolean revoked = oauth.revoke(refreshToken);
                log.info("Gmail grant revocation accountId={} revoked={}", account.id(), revoked);
            } catch (TokenUnreadableException e) {
                log.warn("Gmail token could not be decrypted for revocation accountId={}", account.id());
            }
        }
        tokens.evict(account.id());
        accounts.disconnect(account.id(), clock.instant());
        log.info("Gmail account disconnected accountId={}", account.id());
    }

    /**
     * @param browserBinding value for the HttpOnly cookie that ties the callback to this browser
     */
    public record PendingAuthorization(String authorizationUrl, String browserBinding) {
        @Override
        public String toString() {
            return "PendingAuthorization[]";
        }
    }
}

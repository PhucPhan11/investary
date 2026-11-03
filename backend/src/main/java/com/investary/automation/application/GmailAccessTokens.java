package com.investary.automation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.investary.automation.google.GmailApiClient.TokenSource;
import com.investary.automation.google.GoogleExceptions.InvalidGrantException;
import com.investary.automation.google.GoogleOAuthClient;
import com.investary.automation.google.GoogleOAuthClient.Tokens;
import com.investary.automation.persistence.GmailAccountStore;
import com.investary.automation.persistence.GmailAccountStore.AccountRow;
import com.investary.automation.security.TokenCipher;
import com.investary.automation.security.TokenCipher.TokenUnreadableException;

/**
 * Server-side access tokens. Access tokens live only in this in-memory cache; the refresh token is read
 * from the database and decrypted only to refresh. If Google answers {@code invalid_grant}, or the stored
 * token cannot be decrypted (the key changed), the account is switched to {@code RECONNECT_REQUIRED}.
 */
@Component
public class GmailAccessTokens {

    private static final Logger log = LoggerFactory.getLogger(GmailAccessTokens.class);
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final GmailAccountStore accounts;
    private final GoogleOAuthClient oauth;
    private final TokenCipher cipher;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public GmailAccessTokens(GmailAccountStore accounts, GoogleOAuthClient oauth, TokenCipher cipher, Clock clock) {
        this.accounts = accounts;
        this.oauth = oauth;
        this.cipher = cipher;
        this.clock = clock;
    }

    public TokenSource source(UUID accountId) {
        return forceRefresh -> accessToken(accountId, forceRefresh);
    }

    /** Remembers the access token returned by the authorization code exchange. */
    public void put(UUID accountId, Tokens tokens) {
        cache.put(accountId, new Cached(tokens.accessToken(), expiry(tokens)));
    }

    public void evict(UUID accountId) {
        cache.remove(accountId);
    }

    /**
     * @throws ReconnectRequiredException if there is no usable refresh token
     */
    public String accessToken(UUID accountId, boolean forceRefresh) {
        Instant now = clock.instant();
        Cached cached = cache.get(accountId);
        if (!forceRefresh && cached != null && cached.expiresAt().isAfter(now)) {
            return cached.token();
        }
        AccountRow account = accounts.find(accountId)
                .orElseThrow(() -> new ReconnectRequiredException("NOT_CONNECTED", "The Gmail account is no longer connected"));
        if (account.refreshTokenCiphertext() == null) {
            throw new ReconnectRequiredException("RECONNECT_REQUIRED", "No Gmail authorization is stored; reconnect Gmail");
        }
        String refreshToken;
        try {
            refreshToken = cipher.decrypt(account.refreshTokenCiphertext(), account.tokenKeyId(), accountId);
        } catch (TokenUnreadableException e) {
            log.warn("Stored Gmail token cannot be decrypted (key changed?) accountId={}", accountId);
            reconnectRequired(accountId, "TOKEN_UNREADABLE",
                    "The stored Gmail authorization cannot be decrypted (the token key changed); reconnect Gmail");
            throw new ReconnectRequiredException("TOKEN_UNREADABLE", "The stored Gmail authorization cannot be decrypted");
        }
        try {
            Tokens tokens = oauth.refresh(refreshToken);
            put(accountId, tokens);
            log.debug("Gmail access token refreshed accountId={}", accountId);
            return tokens.accessToken();
        } catch (InvalidGrantException e) {
            log.warn("Google rejected the stored Gmail authorization accountId={}", accountId);
            reconnectRequired(accountId, "RECONNECT_REQUIRED", e.getMessage());
            throw new ReconnectRequiredException("RECONNECT_REQUIRED", e.getMessage());
        }
    }

    private void reconnectRequired(UUID accountId, String code, String message) {
        evict(accountId);
        accounts.markReconnectRequired(accountId, code, message, clock.instant());
    }

    private Instant expiry(Tokens tokens) {
        long seconds = Math.max(0, tokens.expiresInSeconds() - EXPIRY_SKEW_SECONDS);
        return clock.instant().plusSeconds(seconds);
    }

    private record Cached(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "Cached[expiresAt=" + expiresAt + "]";
        }
    }

    /** The account needs the user to connect again; syncing stops until then. */
    public static class ReconnectRequiredException extends RuntimeException {
        private final String code;

        public ReconnectRequiredException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

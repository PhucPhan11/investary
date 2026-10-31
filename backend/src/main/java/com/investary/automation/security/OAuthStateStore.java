package com.investary.automation.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.investary.automation.GmailProperties;

/**
 * Pending OAuth authorizations, kept in memory (single-user, local application).
 * <p>
 * Each authorization has a random {@code state} sent to Google, a PKCE verifier that never leaves the
 * server, and a random browser binding stored in an HttpOnly cookie. A callback is accepted only once,
 * before the TTL, and only from the browser that started it (login CSRF protection). Only hashes of
 * the state and binding are kept. The store is bounded; the oldest entries are dropped first.
 */
@Component
public class OAuthStateStore {

    static final int MAX_PENDING = 20;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final GmailProperties properties;
    private final Clock clock;

    public OAuthStateStore(GmailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts an authorization: returns the state, the PKCE values and the cookie binding. */
    public synchronized Authorization start() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> !p.expiresAt().isAfter(now));
        while (pending.size() >= MAX_PENDING) {
            pending.entrySet().stream().min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(oldest -> pending.remove(oldest.getKey()));
        }
        String state = randomToken();
        String binding = randomToken();
        String verifier = randomToken() + randomToken(); // 86 characters, within RFC 7636's 43..128
        pending.put(hash(state), new Pending(verifier, hash(binding), now.plus(properties.stateTtl())));
        return new Authorization(state, binding, verifier, challenge(verifier));
    }

    /**
     * Consumes a state. The entry is removed whatever the outcome, so a state can never be replayed.
     *
     * @return the PKCE verifier if the state is known, unexpired and was started by this browser
     */
    public synchronized Optional<String> consume(String state, String binding) {
        if (state == null || state.isBlank() || state.length() > 200) {
            return Optional.empty();
        }
        Pending entry = pending.remove(hash(state));
        if (entry == null || !entry.expiresAt().isAfter(clock.instant()) || binding == null
                || !MessageDigest.isEqual(entry.bindingHash().getBytes(StandardCharsets.US_ASCII),
                        hash(binding).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return Optional.of(entry.verifier());
    }

    synchronized int size() {
        return pending.size();
    }

    /** PKCE {@code S256}: base64url(SHA-256(verifier)) without padding. */
    public static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Authorization(String state, String binding, String codeVerifier, String codeChallenge) {
    }

    private record Pending(String verifier, String bindingHash, Instant expiresAt) {
    }
}

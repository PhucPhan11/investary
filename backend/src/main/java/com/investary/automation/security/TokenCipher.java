package com.investary.automation.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.investary.automation.GmailProperties;
import com.investary.common.util.Hashing;

/**
 * Encrypts OAuth refresh tokens at rest with AES-256-GCM.
 * <p>
 * Format: {@code version (1) | iv (12) | ciphertext + tag}. The account id is the additional
 * authenticated data, so a ciphertext copied to another account row does not decrypt. The key id is a
 * short fingerprint of the key: a changed key is detected instead of producing a decryption error.
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(GmailProperties properties) {
        SecretKeySpec parsed = null;
        String id = null;
        if (properties.tokenKey() != null && !properties.tokenKey().isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(properties.tokenKey().strip());
                if (bytes.length == 32) {
                    parsed = new SecretKeySpec(bytes, "AES");
                    id = Hashing.sha256(bytes).substring(0, 16);
                } else {
                    log.warn("INVESTARY_GMAIL_TOKEN_KEY must be 32 bytes (base64); Gmail integration is disabled");
                }
            } catch (IllegalArgumentException e) {
                log.warn("INVESTARY_GMAIL_TOKEN_KEY is not valid base64; Gmail integration is disabled");
            }
        }
        this.key = parsed;
        this.keyId = id;
    }

    public boolean isAvailable() {
        return key != null;
    }

    /** Fingerprint of the current key (stored next to each ciphertext). */
    public String keyId() {
        requireKey();
        return keyId;
    }

    public byte[] encrypt(String plaintext, UUID accountId) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(accountId));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length).put(VERSION).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    /**
     * @throws TokenUnreadableException if the key changed, or the data was altered or belongs to another account
     */
    public String decrypt(byte[] data, String storedKeyId, UUID accountId) {
        requireKey();
        if (data == null || !keyId.equals(storedKeyId) || data.length < 1 + IV_BYTES + TAG_BITS / 8 || data[0] != VERSION) {
            throw new TokenUnreadableException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, IV_BYTES));
            cipher.updateAAD(aad(accountId));
            byte[] plain = cipher.doFinal(data, 1 + IV_BYTES, data.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new TokenUnreadableException();
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("No token encryption key is configured");
        }
    }

    private static byte[] aad(UUID accountId) {
        return accountId.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** The stored token cannot be decrypted with the current key; the account must be reconnected. */
    public static class TokenUnreadableException extends RuntimeException {
        public TokenUnreadableException() {
            super("Stored token cannot be decrypted");
        }
    }
}

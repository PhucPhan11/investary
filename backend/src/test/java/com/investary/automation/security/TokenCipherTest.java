package com.investary.automation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.investary.automation.GmailProperties;
import com.investary.automation.security.TokenCipher.TokenUnreadableException;

class TokenCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    static TokenCipher cipher(String key) {
        return new TokenCipher(new GmailProperties("id", "secret", key, null, null, null, null, null, null, null, null, 0,
                null, null, null, null));
    }

    @Test
    void roundTripsWithoutStoringThePlaintext() {
        TokenCipher cipher = cipher(KEY);
        UUID account = UUID.randomUUID();
        byte[] sealed = cipher.encrypt("1//refresh-token-value", account);

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("refresh-token-value");
        assertThat(cipher.decrypt(sealed, cipher.keyId(), account)).isEqualTo("1//refresh-token-value");
        assertThat(cipher.encrypt("1//refresh-token-value", account)).as("random IV").isNotEqualTo(sealed);
    }

    @Test
    void rejectsTamperingAnotherAccountAndAnotherKey() {
        TokenCipher cipher = cipher(KEY);
        UUID account = UUID.randomUUID();
        byte[] sealed = cipher.encrypt("secret", account);

        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(tampered, cipher.keyId(), account)).isInstanceOf(TokenUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, cipher.keyId(), UUID.randomUUID())).isInstanceOf(TokenUnreadableException.class);

        TokenCipher rotated = cipher(OTHER_KEY);
        assertThat(rotated.keyId()).isNotEqualTo(cipher.keyId());
        assertThatThrownBy(() -> rotated.decrypt(sealed, cipher.keyId(), account)).isInstanceOf(TokenUnreadableException.class);
    }

    @Test
    void isUnavailableWithoutAValidKey() {
        assertThat(cipher(null).isAvailable()).isFalse();
        assertThat(cipher("not base64!").isAvailable()).isFalse();
        assertThat(cipher(Base64.getEncoder().encodeToString(new byte[16])).isAvailable()).isFalse();
        assertThat(cipher(KEY).isAvailable()).isTrue();
    }
}

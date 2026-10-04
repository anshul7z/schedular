package com.data.schedular.service;

import com.data.schedular.config.SchedularProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final SecretCipher cipher = new SecretCipher(new SchedularProperties(KEY, null, null, null));

    @Test
    void roundTrips() {
        String encrypted = cipher.encrypt("s3cr3t-pässwörd");

        assertThat(encrypted).startsWith("v1:").doesNotContain("s3cr3t");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("s3cr3t-pässwörd");
    }

    @Test
    void usesFreshIvEachTime() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void passesNullThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void rejectsDataEncryptedWithAnotherKey() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        SecretCipher other = new SecretCipher(
                new SchedularProperties(Base64.getEncoder().encodeToString(otherKey), null, null, null));

        assertThatThrownBy(() -> cipher.decrypt(other.encrypt("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key");
    }

    @Test
    void rejectsMissingOrWrongSizeKey() {
        assertThatThrownBy(() -> new SecretCipher(new SchedularProperties("", null, null, null)))
                .hasMessageContaining("SCHEDULAR_SECRET_KEY");
        assertThatThrownBy(() -> new SecretCipher(
                new SchedularProperties(Base64.getEncoder().encodeToString(new byte[16]), null, null, null)))
                .hasMessageContaining("32 bytes");
    }
}

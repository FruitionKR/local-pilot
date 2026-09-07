package fruition.access.user.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void roundTripsSecret() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY);
        byte[] secret = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);

        MfaSecretCipher.Encrypted encrypted = cipher.encrypt(secret);

        assertThat(encrypted.ciphertext()).isNotEqualTo(secret);
        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.nonce())).isEqualTo(secret);
    }

    /** 같은 평문이라도 nonce가 달라 암호문이 매번 달라야 한다. */
    @Test
    void usesFreshNoncePerEncryption() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY);
        byte[] secret = "same-secret-value".getBytes(StandardCharsets.UTF_8);

        assertThat(cipher.encrypt(secret).nonce()).isNotEqualTo(cipher.encrypt(secret).nonce());
    }

    /** 키가 없으면 조용히 평문으로 떨어지지 않고 부팅에 실패해야 한다. */
    @Test
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> new MfaSecretCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MFA_ENCRYPTION_KEY");
    }

    @Test
    void wrongLengthKeyFailsFast() {
        assertThatThrownBy(() -> new MfaSecretCipher("c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }
}

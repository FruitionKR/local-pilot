package fruition.access.user.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP secret 암·복호화(AES-GCM).
 *
 * <p>secret은 검증에 원문이 필요해 해시로 둘 수 없다. 평문 저장은 DB 유출 시 MFA를 통째로
 * 무력화하므로 암호화한다. 키가 없으면 부팅을 실패시킨다 — 조용히 평문으로 떨어지면 안 된다.
 */
@Component
public class MfaSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaSecretCipher(@Value("${app.auth.mfa.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "MFA secret 암호화 키가 없습니다. MFA_ENCRYPTION_KEY(base64 32바이트)를 설정하세요.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY가 base64가 아닙니다.", exception);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "MFA_ENCRYPTION_KEY는 base64로 인코딩한 32바이트여야 합니다. 현재 " + keyBytes.length + "바이트.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public Encrypted encrypt(byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new Encrypted(cipher.doFinal(plaintext), nonce);
        } catch (Exception exception) {
            throw new IllegalStateException("MFA secret 암호화에 실패했습니다.", exception);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception exception) {
            throw new IllegalStateException("MFA secret 복호화에 실패했습니다.", exception);
        }
    }

    public record Encrypted(byte[] ciphertext, byte[] nonce) {}
}

package fruition.access.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 메일 링크에 실어 보내는 1회용 불투명 토큰. 발급 원문은 수신자에게만 주고
 * DB에는 SHA-256 해시만 저장한다 — DB가 유출돼도 링크를 재구성할 수 없다.
 */
public final class OpaqueTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OpaqueTokens() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}

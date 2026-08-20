package fruition.access.security.oauth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * OAuth 로그인 성공 후 프런트로 전달하는 1회용 교환 코드 저장소.
 * 다중 인스턴스에서 어느 인스턴스로 교환 요청이 와도 소비할 수 있도록 Redis에 저장한다.
 * consume은 GETDEL로 원자적 1회 사용을 보장하고, 만료는 Redis TTL이 처리한다.
 */
@Component
public class OAuthExchangeCodeStore {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "oauth:exchange:";

    private final StringRedisTemplate redisTemplate;

    public OAuthExchangeCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issue(String userId) {
        String code = generateCode();
        redisTemplate.opsForValue().set(KEY_PREFIX + code, userId, TTL);
        return code;
    }

    public Optional<String> consume(String code) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code));
    }

    private String generateCode() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

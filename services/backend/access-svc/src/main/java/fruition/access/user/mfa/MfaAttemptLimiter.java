package fruition.access.user.mfa;

import fruition.access.user.exception.MfaRateLimitedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** 사용자별 검증 예산. DB 롤백이나 challenge 재발급으로 초기화되지 않는다. */
@Component
public class MfaAttemptLimiter {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 300) end
            if count > 5 then return redis.call('TTL', KEYS[1]) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public MfaAttemptLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void check(String userId) {
        Long retryAfter = redis.execute(CHECK, List.of("auth:mfa:attempts:" + userId));
        if (retryAfter == null) throw new IllegalStateException("MFA 시도 제한을 확인하지 못했습니다.");
        if (retryAfter != 0) throw new MfaRateLimitedException(Math.max(1, retryAfter));
    }
}

package fruition.access.user.service;

import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Service
public class EmailAvailabilityRateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long windowSeconds;
    private final long ipLimit;
    private final long emailLimit;

    public EmailAvailabilityRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.email-availability.window-seconds:60}") long windowSeconds,
            @Value("${app.auth.email-availability.ip-limit:30}") long ipLimit,
            @Value("${app.auth.email-availability.email-limit:5}") long emailLimit) {
        this.redisTemplate = redisTemplate;
        this.windowSeconds = windowSeconds;
        this.ipLimit = ipLimit;
        this.emailLimit = emailLimit;
    }

    public void check(String email, String clientAddress) {
        enforce("ip", clientAddress, ipLimit);
        enforce("email", email.trim().toLowerCase(), emailLimit);
    }

    private void enforce(String scope, String value, long limit) {
        String key = "auth:email-availability:" + scope + ":" + sha256(value);
        Long count = redisTemplate.execute(
                INCREMENT_WITH_TTL,
                List.of(key),
                String.valueOf(windowSeconds));
        if (count != null && count > limit) {
            throw new EmailAvailabilityRateLimitedException(windowSeconds);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}

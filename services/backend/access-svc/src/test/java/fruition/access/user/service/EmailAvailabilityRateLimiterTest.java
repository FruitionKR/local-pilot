package fruition.access.user.service;

import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class EmailAvailabilityRateLimiterTest {

    @Mock StringRedisTemplate redisTemplate;

    EmailAvailabilityRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new EmailAvailabilityRateLimiter(redisTemplate, 60, 30, 5);
    }

    @Test
    void check_ipLimitExceeded_throwsRateLimited() {
        doReturn(31L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> rateLimiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(EmailAvailabilityRateLimitedException.class);
    }

    @Test
    void check_emailLimitExceeded_throwsRateLimited() {
        doReturn(1L, 6L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> rateLimiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(EmailAvailabilityRateLimitedException.class);
    }
}

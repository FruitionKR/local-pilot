package fruition.access.security.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthExchangeCodeStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private OAuthExchangeCodeStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new OAuthExchangeCodeStore(redisTemplate);
    }

    @Test
    void issue_storesUserIdInRedisWithTtl_andReturnsRandomCode() {
        String code = store.issue("user_1f9a74af");

        assertThat(code).isNotBlank();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("user_1f9a74af"), eq(Duration.ofSeconds(60)));
        assertThat(keyCaptor.getValue()).isEqualTo("oauth:exchange:" + code);
    }

    @Test
    void issue_generatesDifferentCodesEachTime() {
        assertThat(store.issue("user_1f9a74af")).isNotEqualTo(store.issue("user_1f9a74af"));
    }

    @Test
    void consume_deletesAtomicallyViaGetAndDelete_andReturnsUserId() {
        when(valueOperations.getAndDelete("oauth:exchange:code-1")).thenReturn("user_1f9a74af");

        assertThat(store.consume("code-1")).contains("user_1f9a74af");
    }

    @Test
    void consume_unknownOrAlreadyConsumedCode_returnsEmpty() {
        when(valueOperations.getAndDelete("oauth:exchange:unknown-code")).thenReturn(null);

        assertThat(store.consume("unknown-code")).isEmpty();
    }
}

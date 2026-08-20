package fruition.access.workspace.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

@ExtendWith(MockitoExtension.class)
class AuthzProjectionStoreTest {

    @Mock StringRedisTemplate redisTemplate;

    @Test
    void evict_deletesSingleKey() {
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        store.evict("ws_1", "user_1");

        verify(redisTemplate).delete("authz:role:ws_1:user_1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictWorkspace_scansWorkspacePrefixAndDeletesAllMatches() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("authz:role:ws_1:user_1", "authz:role:ws_1:user_2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        store.evictWorkspace("ws_1");

        var optionsCaptor = forClass(ScanOptions.class);
        verify(redisTemplate).scan(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getPattern()).isEqualTo("authz:role:ws_1:*");
        verify(redisTemplate).delete(List.of("authz:role:ws_1:user_1", "authz:role:ws_1:user_2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictWorkspace_noMatches_doesNotDelete() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        AuthzProjectionStore store = new AuthzProjectionStore(redisTemplate);

        store.evictWorkspace("ws_1");

        verify(redisTemplate, never()).delete(anyList());
    }
}

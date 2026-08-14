package fruition.access.workspace.service;

import fruition.access.workspace.domain.WorkspaceRole;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 워크스페이스 권한 projection(access 소유).
 *
 * <p>core(문서 서비스)가 access의 DB를 직접 읽지 않도록 멤버십 역할을
 * Redis({@code authz:role:{workspaceId}:{userId}})에 write-through 한다.
 * 멤버십이 변하는 지점에서 put/evict를 호출하고, TTL이 최종 안전망이다.
 */
@Component
public class AuthzProjectionStore {

    private static final Logger log = LoggerFactory.getLogger(AuthzProjectionStore.class);

    private static final String KEY_PREFIX = "authz:role:";
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final int SCAN_COUNT = 100;

    private final StringRedisTemplate redisTemplate;

    public AuthzProjectionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void put(String workspaceId, String userId, WorkspaceRole role) {
        redisTemplate.opsForValue().set(key(workspaceId, userId), role.name(), TTL);
        log.debug("[인가 projection 저장] workspaceId={} userId={} role={} ttlSeconds={}",
                workspaceId, userId, role, TTL.toSeconds());
    }

    public void evict(String workspaceId, String userId) {
        redisTemplate.delete(key(workspaceId, userId));
        log.debug("[인가 projection 삭제] workspaceId={} userId={}", workspaceId, userId);
    }

    /** 워크스페이스 삭제·복구처럼 멤버 전원의 판정이 바뀌는 경우 workspace 단위로 무효화한다. */
    public void evictWorkspace(String workspaceId) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + workspaceId + ":*").count(SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        log.debug("[인가 projection workspace 삭제] workspaceId={} deletedCount={}", workspaceId, keys.size());
    }

    private String key(String workspaceId, String userId) {
        return KEY_PREFIX + workspaceId + ":" + userId;
    }
}

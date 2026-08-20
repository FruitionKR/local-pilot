package fruition.access.workspace.service;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 워크스페이스 권한 projection(access 소유).
 *
 * <p>core(문서 서비스)가 access의 DB를 직접 읽지 않도록 멤버십 역할을
 * Redis({@code authz:role:{workspaceId}:{userId}})로 조회하게 한다. 적재는
 * 문서 서비스가 cache miss 시 내부 API 폴백 결과를 캐시하는 방식이고,
 * 멤버십이 변하는 지점에서 evict를 호출하며 TTL이 최종 안전망이다.
 */
@Component
public class AuthzProjectionStore {

    private static final Logger log = LoggerFactory.getLogger(AuthzProjectionStore.class);

    private static final String KEY_PREFIX = "authz:role:";
    private static final int SCAN_COUNT = 100;

    private final StringRedisTemplate redisTemplate;

    public AuthzProjectionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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

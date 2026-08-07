package fruition.core.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * query run 상태 저장소. 다중 인스턴스에서 어느 인스턴스가 콜백·조회를 받아도
 * 같은 상태를 보도록 Redis에 JSON으로 저장한다. 만료는 Redis TTL이 처리한다.
 */
@Component
public class QueryRunStore {

    private static final Logger log = LoggerFactory.getLogger(QueryRunStore.class);
    private static final String KEY_PREFIX = "query:run:";
    // 진행 중 run의 안전망 TTL. 정상 경로에서는 failStuck이 훨씬 먼저 종결한다.
    private static final Duration ACTIVE_RUN_TTL = Duration.ofHours(24);
    private static final Duration FINISHED_RUN_TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public QueryRunStore(Clock clock, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public QueryRun create(String workspaceId, String sessionId, String question) {
        String requestId = "query_" + UUID.randomUUID();
        QueryRun run = QueryRun.pending(requestId, workspaceId, sessionId, question, clock.instant());
        write(run, ACTIVE_RUN_TTL);
        log.info("[질의 run 저장] requestId={} sessionId={} status=pending questionLength={}",
                requestId, sessionId, question.length());
        return run;
    }

    public Optional<QueryRun> find(String requestId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + requestId))
                .map(this::deserialize);
    }

    public void markRunning(String requestId) {
        update(requestId, run -> {
            log.info("[질의 run 상태 변경] requestId={} {}->running", requestId, run.status());
            return run.running();
        }, ACTIVE_RUN_TTL);
    }

    public void markCompleted(String requestId, QueryResponse result) {
        update(requestId, run -> {
            log.info("[질의 run 상태 변경] requestId={} {}->completed", requestId, run.status());
            return run.completed(result, clock.instant());
        }, FINISHED_RUN_TTL);
    }

    public void markFailed(String requestId, String errorMessage) {
        update(requestId, run -> {
            log.warn("[질의 run 상태 변경] requestId={} {}->failed error={}",
                    requestId, run.status(), errorMessage);
            return run.failed(errorMessage, clock.instant());
        }, FINISHED_RUN_TTL);
    }

    /**
     * 종료되지 않은(RUNNING/PENDING) run이 생성 후 timeout을 넘겼으면 FAILED로 전이하고 그 requestId 목록을 반환한다.
     * 다중 인스턴스의 스케줄러가 같은 run을 함께 종결할 수 있으나 결과가 같아 무해하다.
     */
    public List<String> failStuck(Duration timeout, String errorMessage) {
        Instant cutoff = clock.instant().minus(timeout);
        List<String> failed = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (failIfStillStuck(key, cutoff, errorMessage)) {
                    failed.add(key.substring(KEY_PREFIX.length()));
                }
            }
        }
        if (!failed.isEmpty()) {
            log.warn("[질의 run 타임아웃 실패 처리] count={} requestIds={}", failed.size(), failed);
        }
        return failed;
    }

    /**
     * WATCH/MULTI로 조건부 실패 전이를 시도한다. 판독과 쓰기 사이에 완료·실패가 끼어들면
     * EXEC가 무효화되어 이미 기록된 결과를 덮어쓰지 않는다(경합 시 먼저 쓴 쪽이 이긴다).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean failIfStillStuck(String key, Instant cutoff, String errorMessage) {
        Boolean transitioned = redisTemplate.execute(new SessionCallback<Boolean>() {
            @Override
            public Boolean execute(RedisOperations operations) {
                operations.watch(key);
                Object json = operations.opsForValue().get(key);
                if (json == null) {
                    operations.unwatch();
                    return false;
                }
                QueryRun run = deserialize((String) json);
                if (run.isFinished() || !run.createdAt().isBefore(cutoff)) {
                    operations.unwatch();
                    return false;
                }
                operations.multi();
                operations.opsForValue().set(key, serialize(run.failed(errorMessage, clock.instant())), FINISHED_RUN_TTL);
                return !operations.exec().isEmpty();
            }
        });
        return Boolean.TRUE.equals(transitioned);
    }

    private void update(String requestId, UnaryOperator<QueryRun> mutation, Duration ttl) {
        find(requestId).ifPresent(run -> write(mutation.apply(run), ttl));
    }

    private void write(QueryRun run, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + run.requestId(), serialize(run), ttl);
    }

    private String serialize(QueryRun run) {
        try {
            return objectMapper.writeValueAsString(run);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("query run 직렬화 실패: " + run.requestId(), e);
        }
    }

    private QueryRun deserialize(String json) {
        try {
            return objectMapper.readValue(json, QueryRun.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("query run 역직렬화 실패", e);
        }
    }
}

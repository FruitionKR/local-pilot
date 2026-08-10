package fruition.core.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    // 별도 취소 계약이 없으므로 진행 중 run은 TTL까지만 보존한다.
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

    public boolean markCompleted(String requestId, QueryResponse result) {
        return finish(requestId, run -> {
            log.info("[질의 run 상태 변경] requestId={} {}->completed", requestId, run.status());
            return run.completed(result, clock.instant());
        });
    }

    public boolean markFailed(String requestId, String errorMessage) {
        return finish(requestId, run -> {
            log.warn("[질의 run 상태 변경] requestId={} {}->failed error={}",
                    requestId, run.status(), errorMessage);
            return run.failed(errorMessage, clock.instant());
        });
    }

    private boolean finish(String requestId, UnaryOperator<QueryRun> mutation) {
        Optional<QueryRun> current = find(requestId);
        if (current.isEmpty() || current.get().isFinished()) {
            return false;
        }
        write(mutation.apply(current.get()), FINISHED_RUN_TTL);
        return true;
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

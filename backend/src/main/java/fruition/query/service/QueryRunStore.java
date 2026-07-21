package fruition.query.service;

import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QueryRunStore {

    private static final Logger log = LoggerFactory.getLogger(QueryRunStore.class);
    private static final Duration FINISHED_RUN_TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final ConcurrentHashMap<String, QueryRun> runs = new ConcurrentHashMap<>();

    public QueryRunStore(Clock clock) {
        this.clock = clock;
    }

    public QueryRun create(String sessionId, String question) {
        String requestId = "query_" + UUID.randomUUID();
        QueryRun run = QueryRun.pending(requestId, sessionId, question, clock.instant());
        runs.put(requestId, run);
        log.info("[질의 run 저장] requestId={} sessionId={} status=pending questionLength={}",
                requestId, sessionId, question.length());
        return run;
    }

    public Optional<QueryRun> find(String requestId) {
        return Optional.ofNullable(runs.get(requestId));
    }

    public void markRunning(String requestId) {
        runs.computeIfPresent(requestId, (id, run) -> {
            log.info("[질의 run 상태 변경] requestId={} {}->running", requestId, run.status());
            return run.running();
        });
    }

    public void markCompleted(String requestId, QueryResponse result) {
        runs.computeIfPresent(requestId, (id, run) -> {
            log.info("[질의 run 상태 변경] requestId={} {}->completed", requestId, run.status());
            return run.completed(result, clock.instant());
        });
    }

    public void markFailed(String requestId, String errorMessage) {
        runs.computeIfPresent(requestId, (id, run) -> {
            log.warn("[질의 run 상태 변경] requestId={} {}->failed error={}",
                    requestId, run.status(), errorMessage);
            return run.failed(errorMessage, clock.instant());
        });
    }

    /**
     * 종료되지 않은(RUNNING/PENDING) run이 생성 후 timeout을 넘겼으면 FAILED로 전이하고 그 requestId 목록을 반환한다.
     * 스캔 이후 정상 종료됐을 수 있으므로 computeIfPresent 내부에서 상태를 재확인해 완료 run은 덮어쓰지 않는다.
     */
    public List<String> failStuck(Duration timeout, String errorMessage) {
        Instant cutoff = clock.instant().minus(timeout);
        List<String> failed = new ArrayList<>();
        runs.forEach((id, run) -> {
            if (!run.isFinished() && run.createdAt().isBefore(cutoff)) {
                runs.computeIfPresent(id, (key, current) -> {
                    if (current.isFinished()) {
                        return current;
                    }
                    failed.add(key);
                    return current.failed(errorMessage, clock.instant());
                });
            }
        });
        if (!failed.isEmpty()) {
            log.warn("[질의 run 타임아웃 실패 처리] count={} requestIds={}", failed.size(), failed);
        }
        return failed;
    }

    public List<String> evictExpired() {
        Instant cutoff = clock.instant().minus(FINISHED_RUN_TTL);
        List<String> expired = new ArrayList<>();
        runs.forEach((id, run) -> {
            if (run.isFinished() && run.completedAt() != null && run.completedAt().isBefore(cutoff)) {
                expired.add(id);
            }
        });
        expired.forEach(runs::remove);
        if (!expired.isEmpty()) {
            log.info("[질의 run 만료 제거] count={} requestIds={}", expired.size(), expired);
        }
        return expired;
    }
}

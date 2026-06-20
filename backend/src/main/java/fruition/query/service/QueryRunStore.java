package fruition.query.service;

import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryResponse;
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

    private static final Duration FINISHED_RUN_TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final ConcurrentHashMap<String, QueryRun> runs = new ConcurrentHashMap<>();

    public QueryRunStore(Clock clock) {
        this.clock = clock;
    }

    public QueryRun create(String question) {
        String requestId = "query_" + UUID.randomUUID();
        QueryRun run = QueryRun.pending(requestId, question, clock.instant());
        runs.put(requestId, run);
        return run;
    }

    public Optional<QueryRun> find(String requestId) {
        return Optional.ofNullable(runs.get(requestId));
    }

    public void markRunning(String requestId) {
        runs.computeIfPresent(requestId, (id, run) -> run.running());
    }

    public void markCompleted(String requestId, QueryResponse result) {
        runs.computeIfPresent(requestId, (id, run) -> run.completed(result, clock.instant()));
    }

    public void markFailed(String requestId, String errorMessage) {
        runs.computeIfPresent(requestId, (id, run) -> run.failed(errorMessage, clock.instant()));
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
        return expired;
    }
}

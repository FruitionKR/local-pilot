package fruition.query.service;

import fruition.query.domain.QueryRun;
import fruition.query.domain.QueryRunStatus;
import fruition.query.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRunStoreTest {

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-20T10:00:00Z"));
    private final QueryRunStore store = new QueryRunStore(clock);

    @Test
    void create_thenFind_returnsPendingRun() {
        QueryRun run = store.create("session_abc123", "질문");

        assertThat(store.find(run.requestId())).contains(run);
        assertThat(run.status()).isEqualTo(QueryRunStatus.PENDING);
    }

    @Test
    void find_unknownRequestId_returnsEmpty() {
        assertThat(store.find("query_unknown")).isEmpty();
    }

    @Test
    void markRunning_thenMarkCompleted_updatesStatusAndResult() {
        QueryRun run = store.create("session_abc123", "질문");
        QueryResponse result = new QueryResponse(null, null, null, null, null, null);

        store.markRunning(run.requestId());
        assertThat(store.find(run.requestId()).orElseThrow().status()).isEqualTo(QueryRunStatus.RUNNING);

        store.markCompleted(run.requestId(), result);
        QueryRun completed = store.find(run.requestId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(QueryRunStatus.COMPLETED);
        assertThat(completed.result()).isSameAs(result);
    }

    @Test
    void markFailed_updatesStatusAndErrorMessage() {
        QueryRun run = store.create("session_abc123", "질문");

        store.markFailed(run.requestId(), "파이프라인 오류");

        QueryRun failed = store.find(run.requestId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(QueryRunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("파이프라인 오류");
    }

    @Test
    void evictExpired_removesOnlyFinishedRunsOlderThanTtl() {
        QueryRun stillRunning = store.create("session_abc123", "진행중 질문");

        QueryRun finished = store.create("session_abc123", "완료된 질문");
        store.markCompleted(finished.requestId(), new QueryResponse(null, null, null, null, null, null));

        clock.advance(Duration.ofMinutes(11));

        List<String> evicted = store.evictExpired();

        assertThat(evicted).containsExactly(finished.requestId());
        assertThat(store.find(finished.requestId())).isEmpty();
        assertThat(store.find(stillRunning.requestId())).isPresent();
    }

    @Test
    void evictExpired_keepsFinishedRunsWithinTtl() {
        QueryRun finished = store.create("session_abc123", "완료된 질문");
        store.markCompleted(finished.requestId(), new QueryResponse(null, null, null, null, null, null));

        clock.advance(Duration.ofMinutes(5));

        assertThat(store.evictExpired()).isEmpty();
        assertThat(store.find(finished.requestId())).isPresent();
    }

    @Test
    void failStuck_failsOnlyUnfinishedRunsOlderThanTimeout() {
        QueryRun oldRunning = store.create("session_abc123", "멈춘 질문");
        store.markRunning(oldRunning.requestId());

        clock.advance(Duration.ofMinutes(6));
        QueryRun recent = store.create("session_abc123", "최근 질문");

        List<String> failed = store.failStuck(Duration.ofMinutes(5), "타임아웃");

        assertThat(failed).containsExactly(oldRunning.requestId());
        QueryRun timedOut = store.find(oldRunning.requestId()).orElseThrow();
        assertThat(timedOut.status()).isEqualTo(QueryRunStatus.FAILED);
        assertThat(timedOut.errorMessage()).isEqualTo("타임아웃");
        assertThat(store.find(recent.requestId()).orElseThrow().status()).isEqualTo(QueryRunStatus.PENDING);
    }

    @Test
    void failStuck_ignoresFinishedRuns() {
        QueryRun finished = store.create("session_abc123", "완료된 질문");
        store.markCompleted(finished.requestId(), new QueryResponse(null, null, null, null, null, null));

        clock.advance(Duration.ofMinutes(6));

        assertThat(store.failStuck(Duration.ofMinutes(5), "타임아웃")).isEmpty();
        assertThat(store.find(finished.requestId()).orElseThrow().status()).isEqualTo(QueryRunStatus.COMPLETED);
    }
}

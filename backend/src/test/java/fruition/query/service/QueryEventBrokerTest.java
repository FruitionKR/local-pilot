package fruition.query.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEventBrokerTest {

    // SseEmitter only flushes through its package-private Handler once a real servlet request
    // initializes it. Before that, sends are queued in ResponseBodyEmitter's private
    // "earlySendAttempts" field, so tests read that field via reflection to verify payloads
    // without needing a running MVC container.
    @SuppressWarnings("unchecked")
    private static List<Object> capturedData(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) field.get(emitter);
        List<Object> data = new ArrayList<>();
        for (ResponseBodyEmitter.DataWithMediaType attempt : attempts) {
            data.add(attempt.getData());
        }
        return data;
    }

    private static List<Map<String, Object>> capturedPayloads(SseEmitter emitter) throws Exception {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (Object item : capturedData(emitter)) {
            if (item instanceof Map<?, ?> map) {
                payloads.add((Map<String, Object>) map);
            }
        }
        return payloads;
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), ZoneOffset.UTC);
    private final QueryEventBroker broker = new QueryEventBroker(clock);

    @Test
    void publish_afterSubscribe_sendsPayloadWithSequenceAndTimestamp() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.publish("query_abc123", "query_started", "질의 처리를 시작했습니다.", Map.of("question", "테스트"));

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(1);
        Map<String, Object> payload = payloads.get(0);
        assertThat(payload.get("request_id")).isEqualTo("query_abc123");
        assertThat(payload.get("sequence")).isEqualTo(1L);
        assertThat(payload.get("received_at")).isEqualTo("2026-06-20T10:00:00Z");
        assertThat(payload.get("stage")).isEqualTo("query_started");
        assertThat(payload.get("message")).isEqualTo("질의 처리를 시작했습니다.");
    }

    @Test
    void publish_multipleEvents_incrementsSequencePerRun() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.publish("query_abc123", "query_started", "시작", null);
        broker.publish("query_abc123", "retrieval_scored", "점수 계산", null);

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).get("sequence")).isEqualTo(1L);
        assertThat(payloads.get(1).get("sequence")).isEqualTo(2L);
    }

    @Test
    void publish_beforeSubscribe_isBufferedAndFlushedOnLateSubscribe() throws Exception {
        broker.publish("query_abc123", "query_started", "시작", null);

        SseEmitter lateEmitter = broker.subscribe("query_abc123");

        assertThat(capturedPayloads(lateEmitter)).hasSize(1);
    }

    @Test
    void complete_sendsCompletedEventToSubscriber() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.complete("query_abc123");

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0).get("status")).isEqualTo("completed");
    }

    @Test
    void fail_sendsFailedEventWithErrorMessage() throws Exception {
        SseEmitter emitter = broker.subscribe("query_abc123");

        broker.fail("query_abc123", "쿼리 파이프라인을 사용할 수 없습니다.");

        List<Map<String, Object>> payloads = capturedPayloads(emitter);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0).get("status")).isEqualTo("failed");
        assertThat(payloads.get(0).get("error")).isEqualTo("쿼리 파이프라인을 사용할 수 없습니다.");
    }

    @Test
    void subscribe_afterComplete_immediatelyReceivesBufferedFinalEvent() throws Exception {
        SseEmitter firstEmitter = broker.subscribe("query_abc123");
        broker.complete("query_abc123");

        SseEmitter lateEmitter = broker.subscribe("query_abc123");

        assertThat(capturedPayloads(lateEmitter)).hasSize(1);
        assertThat(capturedPayloads(lateEmitter).get(0).get("status")).isEqualTo("completed");
    }
}

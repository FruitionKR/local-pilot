package fruition.query.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class QueryEventBroker {

    private static final Logger log = LoggerFactory.getLogger(QueryEventBroker.class);
    private static final int MAX_BUFFERED_EVENTS = 200;

    private final Clock clock;
    private final Map<String, RunChannel> channels = new ConcurrentHashMap<>();

    public QueryEventBroker(Clock clock) {
        this.clock = clock;
    }

    public SseEmitter subscribe(String requestId) {
        RunChannel channel = channels.computeIfAbsent(requestId, id -> new RunChannel());
        SseEmitter emitter = new SseEmitter(0L);
        channel.attach(emitter);
        log.info("[질의 SSE emitter 연결] requestId={} bufferedEvents={}", requestId, channel.bufferSize());
        return emitter;
    }

    public void publish(String requestId, String stage, String message, Map<String, Object> data) {
        RunChannel channel = channels.computeIfAbsent(requestId, id -> new RunChannel());
        long sequence = channel.nextSequence();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", requestId);
        payload.put("sequence", sequence);
        payload.put("received_at", clock.instant().toString());
        payload.put("stage", stage);
        payload.put("message", message);
        payload.put("data", data == null ? Map.of() : data);
        channel.send("query.log", payload);
        log.info("[질의 SSE 이벤트 발행] requestId={} sequence={} event=query.log stage={} dataKeys={} emitterCount={}",
                requestId, sequence, stage, data != null ? data.keySet() : List.of(), channel.emitterCount());
    }

    public void complete(String requestId) {
        RunChannel channel = channels.computeIfAbsent(requestId, id -> new RunChannel());
        channel.send("query.completed", Map.of("request_id", requestId, "status", "completed"));
        log.info("[질의 SSE 완료 이벤트 발행] requestId={} emitterCount={}", requestId, channel.emitterCount());
        channel.close();
    }

    public void fail(String requestId, String errorMessage) {
        RunChannel channel = channels.computeIfAbsent(requestId, id -> new RunChannel());
        channel.send("query.failed", Map.of("request_id", requestId, "status", "failed", "error", errorMessage));
        log.warn("[질의 SSE 실패 이벤트 발행] requestId={} error={} emitterCount={}",
                requestId, errorMessage, channel.emitterCount());
        channel.close();
    }

    public void dispose(String requestId) {
        channels.remove(requestId);
        log.info("[질의 SSE 채널 제거] requestId={}", requestId);
    }

    public void sendHeartbeat() {
        channels.values().forEach(RunChannel::heartbeat);
    }

    private static final class RunChannel {
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final Deque<SseEvent> buffer = new ConcurrentLinkedDeque<>();
        private final AtomicLong sequence = new AtomicLong(0);
        private volatile boolean closed = false;

        long nextSequence() {
            return sequence.incrementAndGet();
        }

        int emitterCount() {
            return emitters.size();
        }

        int bufferSize() {
            return buffer.size();
        }

        synchronized void attach(SseEmitter emitter) {
            emitters.add(emitter);
            for (SseEvent event : buffer) {
                sendTo(emitter, event);
            }
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(e -> emitters.remove(emitter));
            if (closed) {
                emitter.complete();
            }
        }

        synchronized void send(String eventName, Map<String, Object> payload) {
            SseEvent event = new SseEvent(eventName, payload);
            buffer.addLast(event);
            while (buffer.size() > MAX_BUFFERED_EVENTS) {
                buffer.removeFirst();
            }
            for (SseEmitter emitter : emitters) {
                sendTo(emitter, event);
            }
        }

        synchronized void heartbeat() {
            if (closed) {
                return;
            }
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException e) {
                    emitters.remove(emitter);
                }
            }
        }

        private void sendTo(SseEmitter emitter, SseEvent event) {
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(event.payload()));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }

        synchronized void close() {
            closed = true;
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
            emitters.clear();
        }
    }

    private record SseEvent(String name, Map<String, Object> payload) {}
}

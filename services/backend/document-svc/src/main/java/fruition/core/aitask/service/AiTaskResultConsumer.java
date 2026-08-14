package fruition.core.aitask.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** ai.task.event를 받아 core 상태와 Redis/SSE projection을 갱신한다. */
@Component
public class AiTaskResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final AiTaskResultApplier applier;
    private final QueryRunStore queryRunStore;
    private final QueryEventBroker queryEventBroker;

    public AiTaskResultConsumer(ObjectMapper objectMapper,
                                AiTaskResultApplier applier,
                                QueryRunStore queryRunStore,
                                QueryEventBroker queryEventBroker) {
        this.objectMapper = objectMapper;
        this.applier = applier;
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
    }

    @KafkaListener(
            topics = "${app.ai-task.result-topic}",
            groupId = "${app.ai-task.result-consumer-group}",
            containerFactory = "aiTaskResultKafkaListenerContainerFactory")
    public void consume(String raw) throws Exception {
        JsonNode event = objectMapper.readTree(raw);
        String kind = event.path("kind").asText("unknown");
        String flowId = resolveFlowId(event);
        MDC.put("flowId", flowId);
        long startedAt = System.nanoTime();
        log.info("[AI task 결과 소비 시작] kind={} flowId={}", kind, flowId);
        try {
            if ("ingest".equals(kind)) {
                applier.applyIngest(event);
                return;
            }
            if ("lint".equals(kind)) {
                applier.applyLint(event);
                return;
            }
            if (kind.startsWith("restore_")) {
                applier.applyRestore(event);
                return;
            }
            if ("agent".equals(kind)) {
                applier.applyAgent(event);
                return;
            }
            if (!"query".equals(kind)) {
                log.warn("[AI task 결과 소비 생략] kind={} flowId={} reason=unsupported_kind", kind, flowId);
                return;
            }
            // 진행 이벤트는 SSE로만 중계하고 최종 결과 적용 경로를 타지 않는다.
            if ("progress".equals(event.path("status").asText())) {
                JsonNode payload = event.path("payload");
                queryEventBroker.publish(
                        event.path("run_id").asText(),
                        event.path("event_id").asText(),
                        payload.path("stage").asText(),
                        payload.path("message").asText(),
                        objectMapper.convertValue(
                                payload.path("data"),
                                new TypeReference<Map<String, Object>>() {}));
                return;
            }
            var projection = applier.applyQuery(event);
            if (projection.error() == null) {
                if (queryRunStore.markCompleted(projection.runId(), projection.response())) {
                    queryEventBroker.complete(projection.runId());
                }
            } else if (queryRunStore.markFailed(projection.runId(), projection.error())) {
                queryEventBroker.fail(projection.runId(), projection.error());
            }
        } catch (Exception e) {
            log.error("[AI task 결과 소비 실패] kind={} flowId={} errorType={}",
                    kind, flowId, e.getClass().getSimpleName(), e);
            throw e;
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[AI task 결과 소비 종료] kind={} flowId={} elapsedMs={}", kind, flowId, elapsedMs);
            MDC.remove("flowId");
        }
    }

    private String resolveFlowId(JsonNode event) {
        for (String field : new String[]{"run_id", "request_id", "operation_id", "document_id"}) {
            if (event.hasNonNull(field) && !event.path(field).asText().isBlank()) {
                return event.path(field).asText();
            }
        }
        return "-";
    }
}

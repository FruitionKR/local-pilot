package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** ai.task.event를 받아 core 상태와 Redis/SSE projection을 갱신한다. */
@Component
public class AiTaskResultConsumer {

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

    @KafkaListener(topics = "${app.ai-task.result-topic}", groupId = "${app.ai-task.result-consumer-group}")
    public void consume(String raw) throws Exception {
        JsonNode event = objectMapper.readTree(raw);
        if ("ingest".equals(event.path("kind").asText())) {
            applier.applyIngest(event);
            return;
        }
        if ("lint".equals(event.path("kind").asText())) {
            applier.applyLint(event);
            return;
        }
        if (event.path("kind").asText().startsWith("restore_")) {
            applier.applyRestore(event);
            return;
        }
        if (!"query".equals(event.path("kind").asText())) return;
        var projection = applier.applyQuery(event);
        if (projection.error() == null) {
            queryRunStore.markCompleted(projection.runId(), projection.response());
            queryEventBroker.complete(projection.runId());
        } else {
            queryRunStore.markFailed(projection.runId(), projection.error());
            queryEventBroker.fail(projection.runId(), projection.error());
        }
    }
}

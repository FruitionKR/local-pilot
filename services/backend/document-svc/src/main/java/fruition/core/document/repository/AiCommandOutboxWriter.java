package fruition.core.document.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.AiCommandOutbox;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** AI command를 현재 DB 트랜잭션의 outbox에 저장한다. */
@Component
public class AiCommandOutboxWriter {

    private final AiCommandOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public AiCommandOutboxWriter(AiCommandOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String runId, String topic, String messageKey, Object command) {
        try {
            repository.save(new AiCommandOutbox(
                    UUID.randomUUID().toString(), runId, topic, messageKey,
                    objectMapper.writeValueAsString(command)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI command 직렬화 실패: runId=" + runId, e);
        }
    }
}

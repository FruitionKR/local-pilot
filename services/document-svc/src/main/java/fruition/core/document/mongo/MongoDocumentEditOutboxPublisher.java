package fruition.core.document.mongo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * document_edit_outbox의 미발행 event를 Kafka로 발행한다.
 *
 * <p>Mongo transaction이 state·write·outbox를 원자적으로 커밋하고, 발행은 이 poller가
 * 뒤따라 수행한다(transactional outbox). key=document_id라 같은 문서의 revision event는
 * 같은 partition에서 순서를 유지한다. 발행 실패 시 published 마킹을 남기지 않아
 * 다음 주기에 재시도한다(at-least-once).
 */
@Component
public class MongoDocumentEditOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(MongoDocumentEditOutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final int BATCH_SIZE = 100;

    private final MongoTemplate mongoTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String eventTopic;

    public MongoDocumentEditOutboxPublisher(MongoTemplate mongoTemplate,
                                            KafkaTemplate<String, String> kafkaTemplate,
                                            ObjectMapper objectMapper,
                                            @Value("${app.document-edit.event-topic}") String eventTopic) {
        this.mongoTemplate = mongoTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.eventTopic = eventTopic;
    }

    @Scheduled(fixedDelayString = "${app.document-edit.outbox-poll-interval-ms:1000}")
    public void publishPending() {
        List<MongoDocumentEditOutboxEvent> events;
        try {
            events = mongoTemplate.find(
                    Query.query(Criteria.where("published").is(false))
                            .with(Sort.by(Sort.Direction.ASC, "createdAt"))
                            .limit(BATCH_SIZE),
                    MongoDocumentEditOutboxEvent.class
            );
        } catch (Exception e) {
            log.warn("[문서 편집 outbox 조회 실패] error={}", e.getMessage());
            return;
        }
        for (MongoDocumentEditOutboxEvent event : events) {
            try {
                String payload = objectMapper.writeValueAsString(new DocumentEditEvent(
                        event.getEventId(),
                        event.getEventType(),
                        event.getSchemaVersion(),
                        event.getDocumentId(),
                        event.getWorkspaceId(),
                        event.getRevision(),
                        event.getContentHash(),
                        event.getCreatedAt()
                ));
                kafkaTemplate.send(eventTopic, event.getDocumentId(), payload)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(event.getEventId())),
                        new Update().set("published", true).set("publishedAt", Instant.now()),
                        MongoDocumentEditOutboxEvent.class
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 문서별 순서를 지키기 위해 이번 주기를 중단하고 다음 주기에 처음부터 재시도한다.
                log.warn("[문서 편집 outbox 발행 실패] eventId={} topic={} error={}",
                        event.getEventId(), eventTopic, e.getMessage());
                return;
            }
        }
    }

    record DocumentEditEvent(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId,
            long revision,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("created_at") Instant createdAt
    ) {}
}

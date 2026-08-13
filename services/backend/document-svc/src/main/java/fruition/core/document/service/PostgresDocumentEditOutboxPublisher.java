package fruition.core.document.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PostgresDocumentEditOutboxPublisher {

    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String eventTopic;

    public PostgresDocumentEditOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.document-edit.event-topic}") String eventTopic
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.eventTopic = eventTopic;
    }

    @Scheduled(fixedDelayString = "${app.document-edit.outbox-poll-interval-ms:1000}")
    public void publishPending() {
        List<Event> events;
        try {
            events = jdbcTemplate.query("""
                    SELECT event_id, document_id, workspace_id, revision, content_hash,
                           event_type, schema_version, created_at
                    FROM document_edit_outbox
                    WHERE published = false
                    ORDER BY created_at, event_id
                    LIMIT ?
                    """, (rs, rowNum) -> new Event(
                    rs.getString("event_id"), rs.getString("event_type"),
                    rs.getInt("schema_version"), rs.getString("document_id"),
                    rs.getString("workspace_id"), rs.getLong("revision"),
                    rs.getString("content_hash"), rs.getTimestamp("created_at").toInstant()), BATCH_SIZE);
        } catch (Exception exception) {
            return;
        }
        for (Event event : events) {
            try {
                String payload = objectMapper.writeValueAsString(event);
                kafkaTemplate.send(eventTopic, event.documentId(), payload)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                jdbcTemplate.update("""
                        UPDATE document_edit_outbox
                        SET published = true, published_at = ?
                        WHERE event_id = ? AND published = false
                        """, Timestamp.from(Instant.now()), event.eventId());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                return;
            }
        }
    }

    record Event(
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

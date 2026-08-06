package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 문서 ingest command를 Kafka로 발행한다. HTTP 동기 호출(DocumentProcessingRequester)을 대체한다.
 *
 * <p>run_id는 backend가 생성해 command에 싣는다 — 동기 응답 없이도 문서에 run을 기록하고
 * pipeline-events 콜백의 run_id 대조를 유지하기 위함이다.
 * key=workspace_id라 같은 워크스페이스 작업은 같은 partition에서 순차 처리되고,
 * 워크스페이스 간에는 partition 수만큼 병렬 처리된다.
 */
@Component
public class IngestCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestCommandPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String commandTopic;

    public IngestCommandPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${app.processing.command-topic}") String commandTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic;
    }

    /**
     * command를 발행하고 backend가 생성한 run_id를 반환한다.
     * broker 확인(ack)까지 동기로 기다려, 실패 시 기존 HTTP 실패 처리 경로(문서 실패 마킹)를 그대로 태운다.
     */
    public String publish(String documentId, String userId, String workspaceId, String logCallbackUrl,
                          String selectionMode, String inputMarkdown, boolean chatWiki,
                          String operationId, String resultCallbackUrl) {
        String runId = UUID.randomUUID().toString();
        IngestCommand command = new IngestCommand(
                runId,
                chatWiki ? "chat_wiki" : "document",
                documentId,
                userId,
                workspaceId,
                logCallbackUrl,
                selectionMode,
                inputMarkdown,
                operationId,
                resultCallbackUrl
        );
        try {
            String payload = objectMapper.writeValueAsString(command);
            kafkaTemplate.send(commandTopic, workspaceId, payload)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ingest command 직렬화 실패: documentId=" + documentId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingest command 발행 중 인터럽트: documentId=" + documentId, e);
        } catch (Exception e) {
            throw new IllegalStateException("ingest command 발행 실패: documentId=" + documentId
                    + " topic=" + commandTopic + " error=" + e.getMessage(), e);
        }
        log.info("[ingest command 발행] topic={} runId={} documentId={} workspaceId={} kind={} operationId={}",
                commandTopic, runId, documentId, workspaceId, command.kind(), operationId);
        return runId;
    }

    public record IngestCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("log_callback_url") String logCallbackUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("selection_mode") String selectionMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("input_markdown") String inputMarkdown,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("operation_id") String operationId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("result_callback_url") String resultCallbackUrl
    ) {}
}

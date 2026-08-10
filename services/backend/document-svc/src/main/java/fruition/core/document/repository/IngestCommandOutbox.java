package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.AiCommandOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ingest command를 현재 DB 트랜잭션의 outbox에 저장한다. */
@Component
public class IngestCommandOutbox {

    private final AiCommandOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final String commandTopic;

    public IngestCommandOutbox(AiCommandOutboxRepository repository,
                               ObjectMapper objectMapper,
                               @Value("${app.processing.command-topic}") String commandTopic) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic;
    }

    public void enqueue(String runId, String documentId, String userId, String workspaceId,
                        String logCallbackUrl, String selectionMode, String inputMarkdown,
                        boolean chatWiki, String operationId, String resultCallbackUrl) {
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
            repository.save(new AiCommandOutbox(
                    UUID.randomUUID().toString(),
                    runId,
                    commandTopic,
                    workspaceId,
                    objectMapper.writeValueAsString(command)
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ingest command 직렬화 실패: documentId=" + documentId, e);
        }
    }

    public void enqueueDelete(String documentId, String workspaceId) {
        String commandId = UUID.randomUUID().toString();
        try {
            repository.save(new AiCommandOutbox(
                    commandId,
                    commandId,
                    commandTopic,
                    workspaceId,
                    objectMapper.writeValueAsString(new DeleteCommand("document_deleted", documentId, workspaceId))
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("document delete command 직렬화 실패: documentId=" + documentId, e);
        }
    }

    record IngestCommand(
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

    record DeleteCommand(
            String kind,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId
    ) {}
}

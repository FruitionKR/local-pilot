package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ingest command를 현재 DB 트랜잭션의 outbox에 저장한다. */
@Component
public class IngestCommandOutbox {

    private final AiCommandOutboxWriter writer;
    private final String commandTopic;

    public IngestCommandOutbox(AiCommandOutboxWriter writer,
                               @Value("${app.processing.command-topic}") String commandTopic) {
        this.writer = writer;
        this.commandTopic = commandTopic;
    }

    public void enqueue(String runId, String documentId, String userId, String workspaceId,
                        String selectionMode, String inputMarkdown, boolean chatWiki, String operationId,
                        long sourceRevision, String sourceContentHash) {
        IngestCommand command = new IngestCommand(
                runId,
                chatWiki ? "chat_wiki" : "document",
                documentId,
                userId,
                workspaceId,
                selectionMode,
                inputMarkdown,
                operationId,
                sourceRevision,
                sourceContentHash
        );
        writer.enqueue(runId, commandTopic, documentId, command);
    }

    public void enqueueDelete(String documentId, String workspaceId) {
        String runId = UUID.randomUUID().toString();
        writer.enqueue(runId, commandTopic, documentId,
                new DeleteCommand(runId, "document_deleted", documentId, workspaceId));
    }

    record IngestCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("selection_mode") String selectionMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("input_markdown") String inputMarkdown,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("operation_id") String operationId,
            @JsonProperty("source_revision") long sourceRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("source_content_hash") String sourceContentHash
    ) {}

    record DeleteCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId
    ) {}
}

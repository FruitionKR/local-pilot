package fruition.core.document.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("document_edit_outbox")
public class MongoDocumentEditOutboxEvent {

    @Id
    private String eventId;
    private String documentId;
    private String workspaceId;
    private long revision;
    private String contentHash;
    private String eventType;
    private int schemaVersion;
    private Instant createdAt;
    // Kafka 발행 여부. publisher가 발행 성공 후 true로 마킹한다.
    private boolean published;
    private Instant publishedAt;

    protected MongoDocumentEditOutboxEvent() {}

    public MongoDocumentEditOutboxEvent(
            String eventId,
            String documentId,
            String workspaceId,
            long revision,
            String contentHash,
            Instant createdAt
    ) {
        this.eventId = eventId;
        this.documentId = documentId;
        this.workspaceId = workspaceId;
        this.revision = revision;
        this.contentHash = contentHash;
        this.eventType = "document.edit.saved.v1";
        this.schemaVersion = 1;
        this.createdAt = createdAt;
        this.published = false;
    }

    public String getEventId() { return eventId; }
    public String getDocumentId() { return documentId; }
    public String getWorkspaceId() { return workspaceId; }
    public long getRevision() { return revision; }
    public String getContentHash() { return contentHash; }
    public String getEventType() { return eventType; }
    public int getSchemaVersion() { return schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isPublished() { return published; }
    public Instant getPublishedAt() { return publishedAt; }
}

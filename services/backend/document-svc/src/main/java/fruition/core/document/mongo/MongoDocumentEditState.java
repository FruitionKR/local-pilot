package fruition.core.document.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("document_edit_states")
public class MongoDocumentEditState {

    @Id
    private String documentId;
    private String workspaceId;
    private String markdown;
    private long revision;
    private String contentHash;
    private int schemaVersion;
    private String updatedBy;
    private Instant updatedAt;
    private String lastWriteId;
    private Instant lastWriteAt;

    protected MongoDocumentEditState() {}

    public MongoDocumentEditState(
            String documentId,
            String workspaceId,
            String markdown,
            long revision,
            String contentHash,
            String updatedBy,
            Instant updatedAt
    ) {
        this.documentId = documentId;
        this.workspaceId = workspaceId;
        this.markdown = markdown;
        this.revision = revision;
        this.contentHash = contentHash;
        this.schemaVersion = 1;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getDocumentId() { return documentId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getMarkdown() { return markdown; }
    public long getRevision() { return revision; }
    public String getContentHash() { return contentHash; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLastWriteId() { return lastWriteId; }
    public Instant getLastWriteAt() { return lastWriteAt; }
}

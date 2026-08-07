package fruition.core.document.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("document_edit_writes")
@CompoundIndex(name = "uq_document_edit_write", def = "{'documentId': 1, 'revisionWriteId': 1}", unique = true)
public class MongoDocumentEditWrite {

    @Id
    private String id;
    private String documentId;
    private String revisionWriteId;
    private long baseRevision;
    private String baseMarkdown;
    private String baseContentHash;
    private long resultRevision;
    private String requestContentHash;
    private String requestHash;
    private String actorUserId;
    private boolean changed;
    private Instant resultUpdatedAt;
    private Instant createdAt;

    protected MongoDocumentEditWrite() {}

    public MongoDocumentEditWrite(
            String documentId,
            String revisionWriteId,
            long baseRevision,
            String baseMarkdown,
            String baseContentHash,
            long resultRevision,
            String requestContentHash,
            String requestHash,
            String actorUserId,
            boolean changed,
            Instant resultUpdatedAt,
            Instant createdAt
    ) {
        this.id = id(documentId, revisionWriteId);
        this.documentId = documentId;
        this.revisionWriteId = revisionWriteId;
        this.baseRevision = baseRevision;
        this.baseMarkdown = baseMarkdown;
        this.baseContentHash = baseContentHash;
        this.resultRevision = resultRevision;
        this.requestContentHash = requestContentHash;
        this.requestHash = requestHash;
        this.actorUserId = actorUserId;
        this.changed = changed;
        this.resultUpdatedAt = resultUpdatedAt;
        this.createdAt = createdAt;
    }

    public static String id(String documentId, String revisionWriteId) {
        return documentId + ":" + revisionWriteId;
    }

    public String getId() { return id; }
    public String getDocumentId() { return documentId; }
    public String getRevisionWriteId() { return revisionWriteId; }
    public long getBaseRevision() { return baseRevision; }
    public String getBaseMarkdown() { return baseMarkdown; }
    public String getBaseContentHash() { return baseContentHash; }
    public long getResultRevision() { return resultRevision; }
    public String getRequestContentHash() { return requestContentHash; }
    public String getRequestHash() { return requestHash; }
    public String getActorUserId() { return actorUserId; }
    public boolean isChanged() { return changed; }
    public Instant getResultUpdatedAt() { return resultUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

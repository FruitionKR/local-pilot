package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "document_edit_states")
public class DocumentEditState {

    @Id
    @Column(name = "document_id")
    private String documentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String markdown;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentEditState() {}

    public DocumentEditState(String documentId, String markdown, String contentHash) {
        this.documentId = documentId;
        this.markdown = markdown;
        this.contentHash = contentHash;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String markdown, String contentHash, Instant updatedAt) {
        this.markdown = markdown;
        this.contentHash = contentHash;
        this.updatedAt = updatedAt;
    }

    public String getDocumentId() { return documentId; }
    public String getMarkdown() { return markdown; }
    public String getContentHash() { return contentHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

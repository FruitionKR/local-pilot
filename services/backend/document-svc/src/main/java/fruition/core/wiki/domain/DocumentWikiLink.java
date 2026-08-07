package fruition.core.wiki.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "document_wiki_links")
public class DocumentWikiLink {

    @EmbeddedId
    private DocumentWikiLinkId id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private String workspaceId;

    @Column
    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentWikiLink() {}

    public DocumentWikiLink(String documentId, String wikiPageId,
                            DocumentWikiRelationType relationType, Double confidence,
                            String workspaceId) {
        this.id = new DocumentWikiLinkId(documentId, wikiPageId, relationType);
        this.confidence = confidence;
        this.workspaceId = workspaceId;
        this.createdAt = Instant.now();
    }

    public String getWorkspaceId() { return workspaceId; }
    public String getDocumentId() { return id.getDocumentId(); }
    public String getWikiPageId() { return id.getWikiPageId(); }
    public DocumentWikiRelationType getRelationType() { return id.getRelationType(); }
    public Double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}

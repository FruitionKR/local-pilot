package fruition.wiki.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "document_wiki_links")
public class DocumentWikiLink {

    @EmbeddedId
    private DocumentWikiLinkId id;

    @Column
    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentWikiLink() {}

    public DocumentWikiLink(String documentId, String wikiPageId,
                            DocumentWikiRelationType relationType, Double confidence) {
        this.id = new DocumentWikiLinkId(documentId, wikiPageId, relationType);
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public String getDocumentId() { return id.getDocumentId(); }
    public String getWikiPageId() { return id.getWikiPageId(); }
    public DocumentWikiRelationType getRelationType() { return id.getRelationType(); }
    public Double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}

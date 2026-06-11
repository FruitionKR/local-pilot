package fruition.poc.backend.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class DocumentWikiLinkId implements Serializable {

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "wiki_page_id", nullable = false)
    private String wikiPageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    private DocumentWikiRelationType relationType;

    protected DocumentWikiLinkId() {}

    public DocumentWikiLinkId(String documentId, String wikiPageId, DocumentWikiRelationType relationType) {
        this.documentId = documentId;
        this.wikiPageId = wikiPageId;
        this.relationType = relationType;
    }

    public String getDocumentId() { return documentId; }
    public String getWikiPageId() { return wikiPageId; }
    public DocumentWikiRelationType getRelationType() { return relationType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentWikiLinkId that)) return false;
        return Objects.equals(documentId, that.documentId)
                && Objects.equals(wikiPageId, that.wikiPageId)
                && relationType == that.relationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, wikiPageId, relationType);
    }
}

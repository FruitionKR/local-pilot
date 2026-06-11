package fruition.wiki.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wiki_page_links")
public class WikiPageLink {

    @EmbeddedId
    private WikiPageLinkId id;

    @Column
    private String label;

    @Column
    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WikiPageLink() {}

    public String getFromPageId() { return id.getFromPageId(); }
    public String getToPageId() { return id.getToPageId(); }
    public String getLinkType() { return id.getLinkType(); }
    public String getLabel() { return label; }
    public Double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

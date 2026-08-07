package fruition.core.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class WikiPageLinkId implements Serializable {

    @Column(name = "from_page_id", nullable = false)
    private String fromPageId;

    @Column(name = "to_page_id", nullable = false)
    private String toPageId;

    @Column(name = "link_type", nullable = false)
    private String linkType;

    protected WikiPageLinkId() {}

    public WikiPageLinkId(String fromPageId, String toPageId, String linkType) {
        this.fromPageId = fromPageId;
        this.toPageId = toPageId;
        this.linkType = linkType;
    }

    public String getFromPageId() { return fromPageId; }
    public String getToPageId() { return toPageId; }
    public String getLinkType() { return linkType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikiPageLinkId that)) return false;
        return Objects.equals(fromPageId, that.fromPageId)
                && Objects.equals(toPageId, that.toPageId)
                && Objects.equals(linkType, that.linkType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromPageId, toPageId, linkType);
    }
}

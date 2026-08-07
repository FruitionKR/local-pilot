package fruition.core.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "source_blocks")
public class SourceBlock {

    @EmbeddedId
    private SourceBlockId id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    protected SourceBlock() {}

    public SourceBlock(SourceBlockId id, String text) {
        this.id = id;
        this.text = text;
    }

    public String getDocumentId() { return id.getDocumentId(); }
    public String getBlockId() { return id.getBlockId(); }
    public String getText() { return text; }
}

package fruition.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class SourceBlockId implements Serializable {

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "block_id", nullable = false)
    private String blockId;

    protected SourceBlockId() {}

    public SourceBlockId(String documentId, String blockId) {
        this.documentId = documentId;
        this.blockId = blockId;
    }

    public String getDocumentId() { return documentId; }
    public String getBlockId() { return blockId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SourceBlockId that)) return false;
        return Objects.equals(documentId, that.documentId)
                && Objects.equals(blockId, that.blockId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, blockId);
    }
}

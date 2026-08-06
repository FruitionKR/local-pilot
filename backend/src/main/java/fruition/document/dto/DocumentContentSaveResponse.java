package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record DocumentContentSaveResponse(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("current_version") long currentVersion,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("updated_at") Instant updatedAt,
        boolean changed,
        String markdown,
        List<DocumentAttachmentSaveResponse> attachments
) {
    public DocumentContentSaveResponse(
            String documentId, long currentVersion, String contentHash, Instant updatedAt, boolean changed
    ) {
        this(documentId, currentVersion, contentHash, updatedAt, changed, null, List.of());
    }
}

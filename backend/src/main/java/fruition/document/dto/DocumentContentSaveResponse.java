package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record DocumentContentSaveResponse(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("current_version") long currentVersion,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("updated_at") Instant updatedAt,
        boolean changed
) {
}

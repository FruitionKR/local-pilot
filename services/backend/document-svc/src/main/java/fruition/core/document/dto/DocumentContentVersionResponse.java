package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record DocumentContentVersionResponse(
        @JsonProperty("document_id") String documentId,
        long version,
        String markdown,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("created_at") Instant createdAt
) {
}

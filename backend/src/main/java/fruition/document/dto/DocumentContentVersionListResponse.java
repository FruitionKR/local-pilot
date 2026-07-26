package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record DocumentContentVersionListResponse(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("current_version") long currentVersion,
        List<Item> versions
) {
    public record Item(
            long version,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("created_by") String createdBy,
            @JsonProperty("created_at") Instant createdAt
    ) {}
}

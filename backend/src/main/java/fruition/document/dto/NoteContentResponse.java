package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record NoteContentResponse(
        @JsonProperty("document_id") String documentId,
        String markdown,
        @JsonProperty("content_version") long contentVersion,
        @JsonProperty("updated_at") Instant updatedAt
) {}

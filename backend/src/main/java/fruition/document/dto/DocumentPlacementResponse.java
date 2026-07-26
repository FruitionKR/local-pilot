package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record DocumentPlacementResponse(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("folder_id") UUID folderId,
        @JsonProperty("sort_order") long sortOrder,
        @JsonProperty("current_version") long currentVersion
) {
}

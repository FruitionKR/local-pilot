package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record DocumentPositionResponse(
        String id,
        @JsonProperty("folder_id") UUID folderId,
        @JsonProperty("sort_order") long sortOrder,
        @JsonProperty("current_version") long currentVersion
) {
}

package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record FolderResponse(
        UUID id,
        @JsonProperty("parent_folder_id") UUID parentFolderId,
        String name,
        @JsonProperty("sort_order") long sortOrder,
        @JsonProperty("current_version") long currentVersion
) {
}

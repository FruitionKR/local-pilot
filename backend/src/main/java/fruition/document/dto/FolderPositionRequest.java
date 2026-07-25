package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FolderPositionRequest(
        @JsonProperty("parent_folder_id") UUID parentFolderId,
        @JsonProperty("position") Integer position,
        @NotNull @JsonProperty("base_version") Long baseVersion
) {
}

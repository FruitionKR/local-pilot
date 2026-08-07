package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record FolderCreateRequest(
        @NotBlank String name,
        @JsonProperty("parent_folder_id") UUID parentFolderId
) {
}

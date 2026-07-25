package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FolderRenameRequest(
        @NotBlank String name,
        @NotNull @JsonProperty("base_version") Long baseVersion
) {
}

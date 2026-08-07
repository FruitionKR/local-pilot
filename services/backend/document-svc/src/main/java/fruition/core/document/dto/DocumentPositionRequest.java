package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DocumentPositionRequest(
        @JsonProperty("folder_id") UUID folderId,
        @JsonProperty("position") Integer position,
        @NotNull @JsonProperty("base_version") Long baseVersion
) {
}

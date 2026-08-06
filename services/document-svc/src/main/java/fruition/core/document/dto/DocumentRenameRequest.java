package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record DocumentRenameRequest(
        @JsonProperty("display_name") String displayName,
        @NotNull(message = "base_version은 필수입니다.")
        @JsonProperty("base_version") Long baseVersion
) {}

package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DocumentContentRestoreRequest(
        @NotNull @Min(1) @JsonProperty("base_version") Long baseVersion
) {
}

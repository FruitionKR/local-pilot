package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record DocumentLifecycleRequest(
        @NotNull @JsonProperty("base_version") Long baseVersion
) {
}

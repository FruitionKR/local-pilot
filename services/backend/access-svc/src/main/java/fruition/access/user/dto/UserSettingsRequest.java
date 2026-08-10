package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record UserSettingsRequest(
        @JsonProperty("web_search_enabled") @NotNull Boolean webSearchEnabled
) {}

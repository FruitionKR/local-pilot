package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserSettingsRequest(
        @JsonProperty("web_search_enabled") boolean webSearchEnabled
) {}

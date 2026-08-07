package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record MeResponse(
        String id,
        String email,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("created_at") Instant createdAt
) {}

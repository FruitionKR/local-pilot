package fruition.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record WorkspaceResponse(
        String id,
        String name,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {}

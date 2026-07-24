package fruition.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record WorkspaceLifecycleResponse(
        String id,
        boolean deleted,
        @JsonProperty("deleted_at") Instant deletedAt
) {
}

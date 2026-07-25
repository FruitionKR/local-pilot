package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record FolderLifecycleResponse(
        UUID id,
        @JsonProperty("current_version") long currentVersion,
        boolean deleted,
        @JsonProperty("deleted_at") Instant deletedAt,
        @JsonProperty("delete_operation_id") UUID deleteOperationId
) {
}

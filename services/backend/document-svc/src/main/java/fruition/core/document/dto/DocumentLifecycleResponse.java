package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record DocumentLifecycleResponse(
        String id,
        @JsonProperty("current_version") long currentVersion,
        boolean deleted,
        @JsonProperty("deleted_at") Instant deletedAt,
        @JsonProperty("sort_order") long sortOrder
) {
}

package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record DocumentRenameResponse(
        String id,
        String filename,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("current_version") long currentVersion,
        @JsonProperty("updated_at") Instant updatedAt,
        boolean changed
) {}

package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.document.domain.DocumentStatus;

import java.time.Instant;

public record DocumentRenameResponse(
        String id,
        String filename,
        @JsonProperty("previous_filename") String previousFilename,
        @JsonProperty("source_uri") String sourceUri,
        DocumentStatus status,
        @JsonProperty("renamed_at") Instant renamedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("source_page") SourcePageRef sourcePage
) {
    public record SourcePageRef(String id, String title, boolean renamed) {}
}

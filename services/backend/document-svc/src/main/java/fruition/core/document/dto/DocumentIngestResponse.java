package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentStatus;

public record DocumentIngestResponse(
        String id,
        @JsonProperty("run_id") String runId,
        DocumentStatus status
) {
}

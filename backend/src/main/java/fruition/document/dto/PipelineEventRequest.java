package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record PipelineEventRequest(
        @JsonProperty("run_id") String runId,
        String stage,
        String message,
        String timestamp,
        Map<String, Object> data
) {}

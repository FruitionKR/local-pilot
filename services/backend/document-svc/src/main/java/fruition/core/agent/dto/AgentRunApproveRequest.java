package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRunApproveRequest(
        @JsonProperty("plan_version") @Min(1) int planVersion,
        @JsonProperty("operation_hash") @NotBlank @Size(min = 64, max = 64) String operationHash
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }
}

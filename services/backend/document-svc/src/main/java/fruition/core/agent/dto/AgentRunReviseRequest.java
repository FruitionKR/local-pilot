package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AgentRunReviseRequest(
        @NotBlank @Size(max = 1000) String instruction
) {}

package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record AutoRoutingRequest(@JsonProperty("enabled") @NotNull Boolean enabled) {}

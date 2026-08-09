package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SkillDraftFromRunsRequest(
        @JsonProperty("scope_type") @NotBlank @Pattern(regexp = "personal|team") String scopeType,
        @JsonProperty("source_run_ids") @NotEmpty @Size(max = 5) List<@NotBlank String> sourceRunIds,
        @JsonProperty("user_directives") @Size(max = 10) List<@NotBlank String> userDirectives
) {
    public SkillDraftFromRunsRequest {
        sourceRunIds = sourceRunIds == null ? List.of() : List.copyOf(sourceRunIds);
        userDirectives = userDirectives == null ? List.of() : List.copyOf(userDirectives);
    }
}

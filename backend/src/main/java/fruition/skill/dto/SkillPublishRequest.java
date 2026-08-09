package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SkillPublishRequest(
        @NotNull @Valid SkillDraftRequest draft,
        @JsonProperty("review_token") @NotBlank String reviewToken
) {}

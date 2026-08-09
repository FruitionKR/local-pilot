package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SkillPublishRequest(
        @JsonProperty("scope_type")
        @NotBlank
        @Pattern(regexp = "personal|team")
        String scopeType,
        @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$")
        String name,
        @NotBlank
        @Size(max = 500)
        String description,
        @JsonProperty("instructions_markdown")
        @NotBlank
        @Size(max = 30000)
        String instructionsMarkdown
) {}

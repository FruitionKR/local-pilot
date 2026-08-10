package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SkillAuthoringRequest(
        @JsonProperty("scope_type")
        @NotBlank
        @Pattern(regexp = "personal|team")
        String scopeType,
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$")
        String name,
        @Size(max = 500)
        String description,
        @NotBlank
        @Size(max = 30000)
        String instruction,
        @JsonProperty("authoring_mode")
        @Pattern(regexp = "preserve|enhance|regenerate")
        String authoringMode,
        @JsonProperty("reference_document_ids")
        @Size(max = 3)
        List<@NotBlank String> referenceDocumentIds
) {
    public SkillAuthoringRequest {
        authoringMode = authoringMode == null ? "enhance" : authoringMode;
        referenceDocumentIds = referenceDocumentIds == null ? List.of() : List.copyOf(referenceDocumentIds);
    }
}

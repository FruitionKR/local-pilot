package fruition.wikischema.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record WikiSchemaPreviewRequest(
        @NotBlank @JsonProperty("raw_markdown") String rawMarkdown
) {}

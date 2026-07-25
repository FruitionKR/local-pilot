package fruition.wikischema.dto;

import jakarta.validation.constraints.NotBlank;

public record WikiSchemaPreviewRequest(
        @NotBlank String rawMarkdown
) {}

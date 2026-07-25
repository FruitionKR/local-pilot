package fruition.wikischema.dto;

import jakarta.validation.constraints.NotBlank;

public record WikiSchemaDraftRequest(
        @NotBlank String rawMarkdown,
        String name
) {}

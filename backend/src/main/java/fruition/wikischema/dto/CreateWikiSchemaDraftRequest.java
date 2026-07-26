package fruition.wikischema.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateWikiSchemaDraftRequest(
        @NotBlank @JsonProperty("raw_markdown") String rawMarkdown,
        @JsonProperty("name") String name
) {}

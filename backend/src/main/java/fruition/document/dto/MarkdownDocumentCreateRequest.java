package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MarkdownDocumentCreateRequest(
        @JsonProperty("display_name") String displayName,
        String markdown
) {
}

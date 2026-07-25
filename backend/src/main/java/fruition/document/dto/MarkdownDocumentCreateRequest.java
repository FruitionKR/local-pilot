package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record MarkdownDocumentCreateRequest(
        @JsonProperty("display_name") String displayName,
        String markdown,
        @JsonProperty("folder_id") UUID folderId
) {
}

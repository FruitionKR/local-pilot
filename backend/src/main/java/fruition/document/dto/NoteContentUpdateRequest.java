package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NoteContentUpdateRequest(
        String markdown,
        @JsonProperty("expected_content_version") Long expectedContentVersion
) {}

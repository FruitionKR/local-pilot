package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record WikiPageRenameResponse(
        String id,
        @JsonProperty("page_type") String pageType,
        String title,
        @JsonProperty("previous_title") String previousTitle,
        String slug,
        @JsonProperty("previous_slug") String previousSlug,
        @JsonProperty("slug_updated") boolean slugUpdated,
        @JsonProperty("updated_at") Instant updatedAt
) {}

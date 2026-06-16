package fruition.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WikiPageRenameRequest(
        String title,
        @JsonProperty("update_slug") Boolean updateSlug
) {}

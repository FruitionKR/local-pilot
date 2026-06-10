package fruition.poc.backend.wiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WikiPageDetailResponse(
        String id,
        @JsonProperty("page_type") String pageType,
        String title,
        String slug,
        String summary,
        @JsonProperty("markdown_uri") String markdownUri,
        String markdown,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("source_documents") List<WikiPageSourceDoc> sourceDocuments,
        @JsonProperty("related_pages") List<WikiRelatedPage> relatedPages
) {}

package fruition.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record ChatMessageResponse(
        String id,
        @JsonProperty("pair_id") String pairId,
        String role,
        String content,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("related_pages") List<ChatMessageRelatedPageResponse> relatedPages,
        List<ChatMessageReference> references,
        @JsonProperty("wiki_page_id") String wikiPageId,
        @JsonProperty("partial_wiki_page_ids") List<String> partialWikiPageIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("error_message") String errorMessage
) {}

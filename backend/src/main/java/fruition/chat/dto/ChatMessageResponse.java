package fruition.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record ChatMessageResponse(
        String id,
        String role,
        String content,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("related_pages") List<ChatMessageRelatedPageResponse> relatedPages,
        List<ChatMessageReference> references,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("error_message") String errorMessage
) {}

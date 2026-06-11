package fruition.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record ChatMessageResponse(
        String id,
        String role,
        String content,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        List<ChatMessageReference> references
) {}

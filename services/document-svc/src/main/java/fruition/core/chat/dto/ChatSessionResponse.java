package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ChatSessionResponse(
        String id,
        String title,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("last_message_at") Instant lastMessageAt
) {}

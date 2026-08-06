package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** 편집 잠금 상태. GET /documents/{id}의 edit_lock 필드와 잠금 API 응답에 공용으로 쓴다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EditLockResponse(
        @JsonProperty("holder_user_id") String holderUserId,
        @JsonProperty("holder_display_name") String holderDisplayName,
        @JsonProperty("expires_at") Instant expiresAt
) {}

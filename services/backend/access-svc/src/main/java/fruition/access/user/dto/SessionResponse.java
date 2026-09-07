package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record SessionResponse(
        @JsonProperty("session_id")
        @Schema(description = "세션 ID. 이 값으로 개별 로그아웃한다.", example = "42")
        Long sessionId,

        @JsonProperty("user_agent")
        @Schema(description = "로그인한 기기의 User-Agent 원문. 이 컬럼이 생기기 전 세션은 null이다.",
                example = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", nullable = true)
        String userAgent,

        @Schema(description = "지금 이 요청을 보낸 세션인지 여부", example = "true")
        boolean current,

        @JsonProperty("created_at")
        @Schema(description = "로그인 시각(ISO-8601 UTC)", example = "2026-09-07T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("expires_at")
        @Schema(description = "만료 시각(ISO-8601 UTC)", example = "2026-09-21T04:25:24.371948Z")
        Instant expiresAt
) {}

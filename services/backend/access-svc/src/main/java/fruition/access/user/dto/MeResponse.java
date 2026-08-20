package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record MeResponse(
        @Schema(description = "사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
        String id,

        @Schema(description = "가입 이메일", example = "user@example.com")
        String email,

        @JsonProperty("display_name")
        @Schema(description = "화면에 보여줄 이름", example = "표시 이름")
        String displayName,

        @JsonProperty("created_at")
        @Schema(description = "가입 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt
) {}

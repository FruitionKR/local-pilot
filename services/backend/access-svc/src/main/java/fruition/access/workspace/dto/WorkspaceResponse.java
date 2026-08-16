package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WorkspaceResponse(
        @Schema(description = "워크스페이스 ID. 이후 대부분의 API 경로에 들어간다.",
                example = "ws_9d47a0e9a6324341b47562553b75f92a")
        String id,

        @Schema(description = "워크스페이스 이름", example = "내 워크스페이스")
        String name,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("updated_at")
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt
) {}

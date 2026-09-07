package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.Workspace;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WorkspaceResponse(
        @Schema(description = "워크스페이스 ID. 이후 대부분의 API 경로에 들어간다.",
                example = "ws_9d47a0e9a6324341b47562553b75f92a")
        String id,

        @Schema(description = "워크스페이스 이름", example = "내 워크스페이스")
        String name,

        @JsonProperty("icon_emoji")
        @Schema(description = "아이콘 이모지. 설정하지 않았으면 null이다.", example = "📁", nullable = true)
        String iconEmoji,

        @JsonProperty("icon_url")
        @Schema(description = "아이콘 이미지 조회 경로. 이미지를 설정하지 않았으면 null이다.",
                example = "/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/icon/image", nullable = true)
        String iconUrl,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("updated_at")
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt
) {
    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getIconEmoji(),
                workspace.hasIconImage() ? "/api/workspaces/" + workspace.getId() + "/icon/image" : null,
                workspace.getCreatedAt(),
                workspace.getUpdatedAt());
    }
}

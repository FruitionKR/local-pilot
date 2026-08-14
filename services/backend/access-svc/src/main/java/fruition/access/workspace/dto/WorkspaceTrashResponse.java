package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record WorkspaceTrashResponse(
        @Schema(description = "삭제되어 휴지통에 있는 워크스페이스 목록")
        List<WorkspaceTrashItem> workspaces) {

    public record WorkspaceTrashItem(
            @Schema(description = "워크스페이스 ID", example = "ws_9d47a0e9a6324341b47562553b75f92a")
            String id,

            @Schema(description = "워크스페이스 이름", example = "내 워크스페이스")
            String name,

            @JsonProperty("deleted_at")
            @Schema(description = "삭제된 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant deletedAt,

            @JsonProperty("deleted_by")
            @Schema(description = "삭제한 사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
            String deletedBy
    ) {
    }
}

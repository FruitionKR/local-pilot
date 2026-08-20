package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WorkspaceLifecycleResponse(
        @Schema(description = "대상 워크스페이스 ID", example = "ws_9d47a0e9a6324341b47562553b75f92a")
        String id,

        @Schema(description = "삭제(휴지통) 상태인지 여부. 복구하면 false가 된다.", example = "true")
        boolean deleted,

        @JsonProperty("deleted_at")
        @Schema(description = "삭제된 시각(ISO-8601 UTC). 복구된 경우 null이다.",
                example = "2026-08-13T04:25:24.371948Z")
        Instant deletedAt
) {
}

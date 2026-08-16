package fruition.access.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record WorkspaceListResponse(
        @Schema(description = "내가 활성 멤버인 워크스페이스 목록")
        List<WorkspaceResponse> workspaces) {}

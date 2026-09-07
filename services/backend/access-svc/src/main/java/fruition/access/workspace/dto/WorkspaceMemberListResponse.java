package fruition.access.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record WorkspaceMemberListResponse(
        @Schema(description = "워크스페이스의 활성 멤버 목록. 합류 시각 오름차순.")
        List<WorkspaceMemberResponse> members) {}

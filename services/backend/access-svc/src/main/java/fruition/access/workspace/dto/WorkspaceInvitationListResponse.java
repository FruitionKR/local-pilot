package fruition.access.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record WorkspaceInvitationListResponse(
        @Schema(description = "아직 수락되지 않은 초대 목록. 생성 시각 오름차순.")
        List<WorkspaceInvitationResponse> invitations) {}

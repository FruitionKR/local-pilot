package fruition.access.workspace.controller;

import fruition.access.workspace.dto.WorkspaceInvitationCreateRequest;
import fruition.access.workspace.dto.WorkspaceInvitationListResponse;
import fruition.access.workspace.dto.WorkspaceInvitationResponse;
import fruition.access.workspace.service.WorkspaceInvitationService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/invitations")
@Tag(name = "Workspace Invitations", description = "워크스페이스 초대 API")
public class WorkspaceInvitationController {

    private final WorkspaceInvitationService workspaceInvitationService;

    public WorkspaceInvitationController(WorkspaceInvitationService workspaceInvitationService) {
        this.workspaceInvitationService = workspaceInvitationService;
    }

    @Operation(summary = "멤버 초대",
            description = "이메일 주소로 초대 링크를 발송합니다. 대기 중 초대가 있으면 새 링크로 재발송합니다. OWNER만 호출할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "초대 발송 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceInvitationResponse.class))),
        @ApiResponse(responseCode = "403", description = "OWNER 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 워크스페이스 멤버이거나 같은 주소로 초대가 진행 중임",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "초대 메일 발송 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<WorkspaceInvitationResponse> invite(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody WorkspaceInvitationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workspaceInvitationService.invite(userId, workspaceId, request));
    }

    @Operation(summary = "대기 중 초대 목록",
            description = "아직 수락되지 않은 초대를 반환합니다. OWNER만 호출할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceInvitationListResponse.class))),
        @ApiResponse(responseCode = "403", description = "OWNER 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<WorkspaceInvitationListResponse> listPending(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId) {
        return ResponseEntity.ok(workspaceInvitationService.listPending(userId, workspaceId));
    }

    @Operation(summary = "초대 취소",
            description = "대기 중인 초대를 취소해 링크를 무효화합니다. OWNER만 호출할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "취소 성공"),
        @ApiResponse(responseCode = "403", description = "OWNER 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 대기 중 초대를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{invitation_id}")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "초대 ID", example = "inv_3c1d8e2f4a5b4c6d8e9f0a1b2c3d4e5f")
            @PathVariable("invitation_id") String invitationId) {
        workspaceInvitationService.revoke(userId, workspaceId, invitationId);
        return ResponseEntity.noContent().build();
    }
}

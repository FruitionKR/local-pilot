package fruition.access.workspace.controller;

import fruition.access.workspace.dto.InvitationAcceptResponse;
import fruition.access.workspace.dto.InvitationPreviewResponse;
import fruition.access.workspace.service.WorkspaceInvitationService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 초대 링크를 받은 사람이 호출하는 API. 워크스페이스 멤버가 아니어도 접근한다. */
@RestController
@RequestMapping("/api/invitations/{token}")
@Tag(name = "Workspace Invitations", description = "워크스페이스 초대 API")
public class InvitationController {

    private final WorkspaceInvitationService workspaceInvitationService;

    public InvitationController(WorkspaceInvitationService workspaceInvitationService) {
        this.workspaceInvitationService = workspaceInvitationService;
    }

    @Operation(summary = "초대 미리보기",
            description = "초대 링크 화면이 로그인 전에 부르는 조회입니다. 인증이 필요하지 않으며, 토큰을 가진 사람만 도달합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = InvitationPreviewResponse.class))),
        @ApiResponse(responseCode = "404", description = "초대를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 수락된 초대",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "만료된 초대",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<InvitationPreviewResponse> preview(
            @Parameter(description = "초대 링크에 실린 토큰")
            @PathVariable("token") String token) {
        return ResponseEntity.ok(workspaceInvitationService.preview(token));
    }

    @Operation(summary = "초대 수락",
            description = "로그인한 계정을 워크스페이스 멤버로 편입합니다. 로그인한 계정의 이메일이 초대받은 주소와 같아야 합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수락 성공",
            content = @Content(schema = @Schema(implementation = InvitationAcceptResponse.class))),
        @ApiResponse(responseCode = "403", description = "로그인한 계정의 이메일이 초대받은 주소와 다름",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "초대를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 수락된 초대",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "410", description = "만료된 초대",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/accept")
    public ResponseEntity<InvitationAcceptResponse> accept(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "초대 링크에 실린 토큰")
            @PathVariable("token") String token) {
        return ResponseEntity.ok(workspaceInvitationService.accept(userId, token));
    }
}

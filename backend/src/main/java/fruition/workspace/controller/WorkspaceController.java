package fruition.workspace.controller;

import fruition.util.ErrorResponse;
import fruition.workspace.dto.WorkspaceCreateRequest;
import fruition.workspace.dto.WorkspaceListResponse;
import fruition.workspace.dto.WorkspaceLifecycleResponse;
import fruition.workspace.dto.WorkspaceRenameRequest;
import fruition.workspace.dto.WorkspaceResponse;
import fruition.workspace.dto.WorkspaceTrashResponse;
import fruition.workspace.service.WorkspaceService;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces")
@Tag(name = "Workspaces", description = "워크스페이스 CRUD API")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Operation(summary = "워크스페이스 생성", description = "로그인한 사용자 소유의 워크스페이스를 생성합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WorkspaceCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaceService.create(userId, request));
    }

    @Operation(summary = "워크스페이스 목록 조회", description = "로그인한 사용자가 소유한 워크스페이스 목록을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceListResponse.class)))
    })
    @GetMapping
    public ResponseEntity<WorkspaceListResponse> list(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(workspaceService.list(userId));
    }

    @Operation(summary = "워크스페이스 이름 변경", description = "로그인한 사용자가 소유한 워크스페이스의 이름을 변경합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{workspace_id}")
    public ResponseEntity<WorkspaceResponse> rename(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody WorkspaceRenameRequest request) {
        return ResponseEntity.ok(workspaceService.rename(userId, workspaceId, request));
    }

    @Operation(summary = "워크스페이스 삭제", description = "소유한 워크스페이스를 하위 데이터 변경 없이 소프트 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "삭제 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{workspace_id}")
    public ResponseEntity<WorkspaceLifecycleResponse> delete(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(example = "550e8400-e29b-41d4-a716-446655440000")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(workspaceService.delete(userId, workspaceId, idempotencyKey));
    }

    @Operation(summary = "삭제 워크스페이스 목록", description = "소유자가 삭제한 워크스페이스를 반환합니다.")
    @GetMapping("/trash")
    public ResponseEntity<WorkspaceTrashResponse> trash(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(workspaceService.trash(userId));
    }

    @Operation(summary = "워크스페이스 복구", description = "소프트 삭제한 워크스페이스와 기존 하위 데이터의 접근을 복구합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "복구 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "삭제 workspace 또는 소유권을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{workspace_id}/restore")
    public ResponseEntity<WorkspaceLifecycleResponse> restore(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(workspaceService.restore(userId, workspaceId, idempotencyKey));
    }
}

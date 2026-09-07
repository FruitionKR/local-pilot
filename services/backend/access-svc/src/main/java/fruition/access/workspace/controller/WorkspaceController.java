package fruition.access.workspace.controller;

import fruition.shared.util.ErrorResponse;
import fruition.access.workspace.dto.WorkspaceCreateRequest;
import fruition.access.workspace.dto.WorkspaceIconUpdateRequest;
import fruition.access.workspace.dto.WorkspaceListResponse;
import fruition.access.workspace.dto.WorkspaceLifecycleResponse;
import fruition.access.workspace.dto.WorkspaceRenameRequest;
import fruition.access.workspace.dto.WorkspaceResponse;
import fruition.access.workspace.dto.WorkspaceTrashResponse;
import fruition.access.workspace.service.WorkspaceIconService;
import fruition.access.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces")
@Tag(name = "Workspaces", description = "워크스페이스 CRUD API")
public class WorkspaceController {

    private static final CacheControl PRIVATE_CACHE =
            CacheControl.maxAge(1, java.util.concurrent.TimeUnit.HOURS).cachePrivate();

    private final WorkspaceService workspaceService;
    private final WorkspaceIconService workspaceIconService;

    public WorkspaceController(WorkspaceService workspaceService, WorkspaceIconService workspaceIconService) {
        this.workspaceService = workspaceService;
        this.workspaceIconService = workspaceIconService;
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

    @Operation(summary = "워크스페이스 아이콘 변경",
            description = "소유한 워크스페이스의 아이콘 이모지를 설정하거나 지웁니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{workspace_id}/icon")
    public ResponseEntity<WorkspaceResponse> updateIcon(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody WorkspaceIconUpdateRequest request) {
        return ResponseEntity.ok(workspaceIconService.updateIcon(userId, workspaceId, request));
    }

    @Operation(summary = "워크스페이스 아이콘 이미지 업로드",
            description = "아이콘으로 쓸 이미지를 업로드합니다. 이모지가 설정돼 있었다면 함께 사라집니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "업로드 성공",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "400", description = "지원하지 않는 이미지 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "이미지가 너무 큼",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping(value = "/{workspace_id}/icon/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkspaceResponse> updateIconImage(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(workspaceIconService.updateIconImage(userId, workspaceId, file));
    }

    @Operation(summary = "워크스페이스 아이콘 이미지 조회",
            description = "멤버에게 아이콘 이미지 bytes를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 반환"),
        @ApiResponse(responseCode = "304", description = "캐시된 이미지 사용"),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 아이콘 이미지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{workspace_id}/icon/image")
    public ResponseEntity<byte[]> getIconImage(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "워크스페이스 ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            WebRequest webRequest) {
        WorkspaceIconService.IconImage icon = workspaceIconService.readIconImage(userId, workspaceId);
        // 직접 비교하면 W/"..." 약한 validator나 콤마로 이어진 목록에서 304를 놓친다.
        if (webRequest.checkNotModified(icon.hash())) {
            return ResponseEntity.status(304).cacheControl(PRIVATE_CACHE).eTag(icon.hash()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(icon.contentType()))
                .contentLength(icon.bytes().length)
                .cacheControl(PRIVATE_CACHE)
                .eTag(icon.hash())
                .header("X-Content-Type-Options", "nosniff")
                .body(icon.bytes());
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

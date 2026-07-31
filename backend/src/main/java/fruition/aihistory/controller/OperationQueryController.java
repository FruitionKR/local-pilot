package fruition.aihistory.controller;

import fruition.aihistory.dto.OperationLogDetailResponse;
import fruition.aihistory.dto.OperationLogListResponse;
import fruition.aihistory.dto.RestorePreviewResponse;
import fruition.aihistory.service.OperationQueryService;
import fruition.aihistory.service.RestorePreviewService;
import fruition.util.ErrorResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 작업 로그 조회와 복구 미리보기. */
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/ai-operation-logs")
@Tag(name = "AI Operation Logs", description = "AI가 문서·Wiki를 바꾼 이력을 조회하고 복구를 미리 확인합니다.")
public class OperationQueryController {

    private final OperationQueryService queryService;
    private final RestorePreviewService previewService;

    public OperationQueryController(OperationQueryService queryService,
                                    RestorePreviewService previewService) {
        this.queryService = queryService;
        this.previewService = previewService;
    }

    @Operation(summary = "AI 작업 로그 목록",
            description = "최신순으로 반환합니다. 로그 테이블만 읽으며 diff를 계산하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<OperationLogListResponse> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "작업 유형", example = "ingest")
            @RequestParam(value = "type", required = false) String type,
            @Parameter(description = "상태", example = "succeeded")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "이전 응답의 next_cursor")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 100", example = "20")
            @RequestParam(value = "size", required = false) Integer size) {
        return ResponseEntity.ok(queryService.list(workspaceId, userId, type, status, cursor, size));
    }

    @Operation(summary = "AI 작업 로그 상세",
            description = "그 작업이 바꾼 리소스를 함께 반환합니다. 줄 수는 저장된 값이라 계산이 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "작업 또는 워크스페이스를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{operation_id}")
    public ResponseEntity<OperationLogDetailResponse> detail(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "작업 식별자", example = "op_a2_7f3c9")
            @PathVariable("operation_id") String operationId) {
        return ResponseEntity.ok(queryService.detail(workspaceId, userId, operationId));
    }

    @Operation(summary = "복구 미리보기",
            description = "이 시점으로 되돌리면 무엇이 삭제·복원·재작성되는지 계산합니다. "
                    + "기준 작업 이후 같은 문서의 작업을 전부 걷어내며, 그사이에 만들어진 페이지는 삭제됩니다. "
                    + "본문을 읽지 않으며, 응답의 preview_token은 복구 실행에 그대로 전달해야 합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계산 성공"),
            @ApiResponse(responseCode = "404", description = "작업 또는 워크스페이스를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{operation_id}/restore-preview")
    public ResponseEntity<RestorePreviewResponse> restorePreview(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("operation_id") String operationId) {
        return ResponseEntity.ok(previewService.preview(workspaceId, userId, operationId));
    }
}

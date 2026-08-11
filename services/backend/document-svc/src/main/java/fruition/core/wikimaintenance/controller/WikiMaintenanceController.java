package fruition.core.wikimaintenance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.dto.WikiMaintenanceStatusResponse;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/wiki/maintenance")
@Tag(name = "Wiki Maintenance", description = "Wiki 정합성 검사와 유지보수 실행 API")
public class WikiMaintenanceController {

    private final WikiMaintenanceService wikiMaintenanceService;

    public WikiMaintenanceController(WikiMaintenanceService wikiMaintenanceService) {
        this.wikiMaintenanceService = wikiMaintenanceService;
    }

    @Operation(summary = "Wiki 유지보수 상태 조회", description = "워크스페이스 Wiki 유지보수 작업의 현재 상태를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 조회 성공",
            content = @Content(schema = @Schema(implementation = WikiMaintenanceStatusResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/status")
    public ResponseEntity<WikiMaintenanceStatusResponse> status(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiMaintenanceService.status(workspaceId, userId));
    }

    @Operation(summary = "Wiki 정합성 검사", description = "워크스페이스 Wiki 정합성 검사 실행을 비동기 대기열에 등록합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Wiki 정합성 검사 실행이 대기열에 등록됨",
            content = @Content(schema = @Schema(implementation = WikiLintResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 검사 옵션",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/lint")
    public ResponseEntity<JsonNode> lint(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) WikiLintRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(wikiMaintenanceService.lint(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki 정합성 검사 결과 조회", description = "실행 중이거나 완료된 Wiki 정합성 검사 결과를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "결과 조회 성공",
            content = @Content(schema = @Schema(implementation = JsonNode.class))),
        @ApiResponse(responseCode = "404", description = "검사 실행 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/runs/{run_id}")
    public ResponseEntity<JsonNode> run(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "조회할 검사 실행 ID", required = true)
            @PathVariable("run_id") String runId) {
        return ResponseEntity.ok(wikiMaintenanceService.run(workspaceId, userId, runId));
    }

    @Schema(name = "WikiLintResponse", requiredProperties = {"run_id", "operation_id", "status"})
    private static final class WikiLintResponseSchema {
        private String run_id;
        @Schema(nullable = true)
        private String operation_id;
        private String status;
    }
}

package fruition.wikimaintenance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.service.WikiMaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/wiki/maintenance")
@Tag(name = "Wiki Maintenance", description = "Wiki 그래프의 링크와 근거 정합성을 점검하는 관리 API")
public class WikiMaintenanceController {

    private final WikiMaintenanceService wikiMaintenanceService;

    public WikiMaintenanceController(WikiMaintenanceService wikiMaintenanceService) {
        this.wikiMaintenanceService = wikiMaintenanceService;
    }

    @Operation(
            summary = "Wiki 정합성 검사",
            description = "워크스페이스 Wiki를 검사해 고아 링크 등 정합성 문제와 제안 조치를 반환합니다. dry_run 설정에 따라 조치 적용 여부가 결정됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "검사 완료"),
        @ApiResponse(responseCode = "400", description = "잘못된 검사 옵션"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음"),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가")
    })
    @PostMapping("/lint")
    public ResponseEntity<JsonNode> lint(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) WikiLintRequest request) {
        return ResponseEntity.ok(wikiMaintenanceService.lint(workspaceId, userId, request));
    }
}

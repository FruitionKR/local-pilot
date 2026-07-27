package fruition.wiki.maintenance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wiki.maintenance.dto.WikiMaintenanceLintRequest;
import fruition.wiki.maintenance.service.WikiMaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "Wiki Maintenance", description = "Wiki 정합성 점검·정리(lint) API")
public class WikiMaintenanceController {

    private final WikiMaintenanceService wikiMaintenanceService;

    public WikiMaintenanceController(WikiMaintenanceService wikiMaintenanceService) {
        this.wikiMaintenanceService = wikiMaintenanceService;
    }

    @Operation(summary = "Wiki lint(정합성 점검·정리)", description = "Wiki 정합성 문제를 점검하고 정리 제안을 제공합니다(dry_run/실행).")
    @PostMapping("/lint")
    public ResponseEntity<JsonNode> lint(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) WikiMaintenanceLintRequest request) {
        return ResponseEntity.ok(wikiMaintenanceService.lint(workspaceId, userId, request));
    }
}

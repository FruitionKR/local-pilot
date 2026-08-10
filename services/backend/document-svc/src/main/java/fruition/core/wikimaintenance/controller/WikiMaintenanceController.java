package fruition.core.wikimaintenance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.dto.WikiMaintenanceStatusResponse;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
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
public class WikiMaintenanceController {

    private final WikiMaintenanceService wikiMaintenanceService;

    public WikiMaintenanceController(WikiMaintenanceService wikiMaintenanceService) {
        this.wikiMaintenanceService = wikiMaintenanceService;
    }

    @GetMapping("/status")
    public ResponseEntity<WikiMaintenanceStatusResponse> status(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiMaintenanceService.status(workspaceId, userId));
    }

    @PostMapping("/lint")
    public ResponseEntity<JsonNode> lint(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) WikiLintRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(wikiMaintenanceService.lint(workspaceId, userId, request));
    }

    @GetMapping("/runs/{run_id}")
    public ResponseEntity<JsonNode> run(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("run_id") String runId) {
        return ResponseEntity.ok(wikiMaintenanceService.run(workspaceId, userId, runId));
    }
}

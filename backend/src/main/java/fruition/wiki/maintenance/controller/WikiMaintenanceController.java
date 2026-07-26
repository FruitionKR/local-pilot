package fruition.wiki.maintenance.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wiki.maintenance.dto.WikiMaintenanceLintRequest;
import fruition.wiki.maintenance.service.WikiMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    @PostMapping("/lint")
    public ResponseEntity<JsonNode> lint(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) WikiMaintenanceLintRequest request) {
        return ResponseEntity.ok(wikiMaintenanceService.lint(workspaceId, userId, request));
    }
}

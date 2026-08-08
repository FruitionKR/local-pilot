package fruition.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.service.SkillService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @PostMapping("/refine")
    public ResponseEntity<JsonNode> refine(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillDraftRequest request) {
        return ResponseEntity.ok(skillService.refine(workspaceId, userId, request));
    }

    @PostMapping("/reviews")
    public ResponseEntity<JsonNode> review(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillDraftRequest request) {
        return ResponseEntity.ok(skillService.review(workspaceId, userId, request));
    }

    @PostMapping
    public ResponseEntity<JsonNode> publish(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillPublishRequest request) {
        return ResponseEntity.ok(skillService.publish(workspaceId, userId, request));
    }
}

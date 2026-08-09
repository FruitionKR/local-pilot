package fruition.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.dto.SkillDraftFromRunsRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.dto.SkillUpdateRequest;
import fruition.skill.service.SkillService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    @PostMapping("/author")
    public ResponseEntity<JsonNode> author(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillAuthoringRequest request) {
        return ResponseEntity.ok(skillService.author(workspaceId, userId, request));
    }

    @PostMapping("/author/publish")
    public ResponseEntity<JsonNode> publish(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillPublishRequest request) {
        return ResponseEntity.ok(skillService.publish(workspaceId, userId, request));
    }

    @PostMapping("/draft-from-runs/preview")
    public ResponseEntity<JsonNode> draftFromRuns(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillDraftFromRunsRequest request) {
        return ResponseEntity.ok(skillService.draftFromRuns(workspaceId, userId, request));
    }

    @GetMapping
    public ResponseEntity<JsonNode> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.list(workspaceId, userId));
    }

    @GetMapping("/{skill_id}")
    public ResponseEntity<JsonNode> get(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.get(workspaceId, userId, skillId));
    }

    @PatchMapping("/{skill_id}")
    public ResponseEntity<JsonNode> update(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillUpdateRequest request) {
        return ResponseEntity.ok(skillService.update(workspaceId, userId, skillId, request));
    }

    @PostMapping("/{skill_id}/enable")
    public ResponseEntity<JsonNode> enable(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.setEnabled(workspaceId, userId, skillId, true));
    }

    @PostMapping("/{skill_id}/disable")
    public ResponseEntity<JsonNode> disable(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.setEnabled(workspaceId, userId, skillId, false));
    }
}

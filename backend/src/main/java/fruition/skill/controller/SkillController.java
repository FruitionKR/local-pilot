package fruition.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.dto.SkillUpdateRequest;
import fruition.skill.dto.AutoRoutingRequest;
import fruition.skill.dto.SkillDetailResponse;
import fruition.skill.dto.SkillSummaryResponse;
import fruition.skill.service.SkillService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ResponseEntity<SkillDetailResponse> publish(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillPublishRequest request) {
        return ResponseEntity.ok(skillService.publish(workspaceId, userId, request));
    }

    @GetMapping
    public ResponseEntity<List<SkillSummaryResponse>> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.list(workspaceId, userId));
    }

    @GetMapping("/{skill_id}")
    public ResponseEntity<SkillDetailResponse> get(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.get(workspaceId, userId, skillId));
    }

    @GetMapping("/commands")
    public ResponseEntity<List<SkillSummaryResponse>> commands(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "") String prefix) {
        return ResponseEntity.ok(skillService.commands(workspaceId, userId, prefix));
    }

    @PutMapping("/{skill_id}")
    public ResponseEntity<SkillDetailResponse> update(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillUpdateRequest request) {
        return ResponseEntity.ok(skillService.update(workspaceId, userId, skillId, request));
    }

    @PatchMapping("/{skill_id}/auto-routing")
    public ResponseEntity<SkillDetailResponse> setAutoRouting(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AutoRoutingRequest request) {
        return ResponseEntity.ok(skillService.setAutoRouting(workspaceId, userId, skillId, request.enabled()));
    }

    @DeleteMapping("/{skill_id}")
    public ResponseEntity<Void> delete(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        skillService.delete(workspaceId, userId, skillId);
        return ResponseEntity.noContent().build();
    }
}

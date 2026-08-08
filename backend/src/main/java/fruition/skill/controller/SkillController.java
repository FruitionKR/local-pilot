package fruition.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.dto.SkillUpdateRequest;
import fruition.skill.dto.AutoRoutingRequest;
import fruition.skill.dto.SkillDetailResponse;
import fruition.skill.dto.SkillSummaryResponse;
import fruition.skill.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Skills", description = "재사용 가능한 Agent Skill의 작성, 검토, 게시 및 실행 설정 API")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @Operation(summary = "Skill 초안 구체화", description = "자연어 요구와 현재 초안을 llmPipeline에 전달해 보완된 Skill 초안을 생성합니다. 저장하거나 게시하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "초안 구체화 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 초안 또는 참조 문서"),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 참조 문서를 찾을 수 없음"),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가")
    })
    @PostMapping("/refine")
    public ResponseEntity<JsonNode> refine(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillDraftRequest request) {
        return ResponseEntity.ok(skillService.refine(workspaceId, userId, request));
    }

    @Operation(summary = "Skill 초안 검토", description = "Skill 초안의 실행 정의와 참조 문서를 검토하고 게시 전 확인 결과를 반환합니다. 저장하거나 게시하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "검토 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 초안"),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 참조 문서를 찾을 수 없음"),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가")
    })
    @PostMapping("/reviews")
    public ResponseEntity<JsonNode> review(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillDraftRequest request) {
        return ResponseEntity.ok(skillService.review(workspaceId, userId, request));
    }

    @Operation(summary = "Skill 게시", description = "검토된 Skill 정의를 게시해 새 버전을 만들고 실행 가능한 상태로 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "게시 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 Skill 정의 또는 검토 token"),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 참조 문서를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "동일 이름 Skill 또는 버전 충돌")
    })
    @PostMapping
    public ResponseEntity<SkillDetailResponse> publish(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillPublishRequest request) {
        return ResponseEntity.ok(skillService.publish(workspaceId, userId, request));
    }

    @Operation(summary = "Skill 목록 조회", description = "현재 사용자가 사용할 수 있는 개인 Skill과 워크스페이스 Skill을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음")
    })
    @GetMapping
    public ResponseEntity<List<SkillSummaryResponse>> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.list(workspaceId, userId));
    }

    @Operation(summary = "Skill 상세 조회", description = "Skill의 현재 게시 버전, 실행 정의, 참조 문서 및 자동 routing 설정을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상세 조회 성공"),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음")
    })
    @GetMapping("/{skill_id}")
    public ResponseEntity<SkillDetailResponse> get(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "Skill ID")
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(skillService.get(workspaceId, userId, skillId));
    }

    @Operation(summary = "Skill 명령어 검색", description = "슬래시 명령 자동완성에 사용할 Skill 목록을 이름 prefix로 필터링해 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "명령어 검색 성공"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음")
    })
    @GetMapping("/commands")
    public ResponseEntity<List<SkillSummaryResponse>> commands(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Skill 이름 prefix. 비우면 전체를 반환합니다.")
            @RequestParam(defaultValue = "") String prefix) {
        return ResponseEntity.ok(skillService.commands(workspaceId, userId, prefix));
    }

    @Operation(summary = "Skill 수정", description = "Skill의 메타데이터와 실행 정의를 수정하고 새 버전을 생성합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 Skill 정의"),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "이름 또는 버전 충돌")
    })
    @PutMapping("/{skill_id}")
    public ResponseEntity<SkillDetailResponse> update(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "수정할 Skill ID")
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SkillUpdateRequest request) {
        return ResponseEntity.ok(skillService.update(workspaceId, userId, skillId, request));
    }

    @Operation(summary = "Skill 자동 routing 설정", description = "일반 Agent 요청에서 이 Skill을 자동 선택할 수 있는지 설정합니다. 슬래시 명령 실행에는 영향을 주지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "설정 변경 성공"),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음")
    })
    @PatchMapping("/{skill_id}/auto-routing")
    public ResponseEntity<SkillDetailResponse> setAutoRouting(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "설정을 변경할 Skill ID")
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AutoRoutingRequest request) {
        return ResponseEntity.ok(skillService.setAutoRouting(workspaceId, userId, skillId, request.enabled()));
    }

    @Operation(summary = "Skill 삭제", description = "현재 사용자가 소유한 Skill을 삭제 상태로 전환해 목록과 실행 대상에서 제외합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(responseCode = "404", description = "Skill 또는 워크스페이스를 찾을 수 없음")
    })
    @DeleteMapping("/{skill_id}")
    public ResponseEntity<Void> delete(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "삭제할 Skill ID")
            @PathVariable("skill_id") String skillId,
            @AuthenticationPrincipal String userId) {
        skillService.delete(workspaceId, userId, skillId);
        return ResponseEntity.noContent().build();
    }
}

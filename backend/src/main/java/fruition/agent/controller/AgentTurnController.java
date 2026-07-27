package fruition.agent.controller;

import fruition.agent.dto.AgentTurnRequest;
import fruition.agent.dto.AgentTurnResponse;
import fruition.agent.service.AgentTurnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/agent")
@Tag(name = "Agent", description = "AI 문서 편집·생성 및 채팅형 답변 API")
public class AgentTurnController {

    private final AgentTurnService agentTurnService;

    public AgentTurnController(AgentTurnService agentTurnService) {
        this.agentTurnService = agentTurnService;
    }

    @Operation(summary = "AI 편집/생성/답변 요청", description = "문서 편집·생성을 AI에 요청하거나 질문합니다. 편집 제안만 반환하며 적용은 별도 저장 API로 수행합니다.")
    @PostMapping("/turn")
    public ResponseEntity<AgentTurnResponse> turn(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AgentTurnRequest request) {
        return ResponseEntity.ok(agentTurnService.turn(workspaceId, userId, request));
    }
}

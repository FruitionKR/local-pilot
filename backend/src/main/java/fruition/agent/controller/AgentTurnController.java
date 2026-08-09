package fruition.agent.controller;

import fruition.agent.dto.AgentTurnRequest;
import fruition.agent.dto.AgentTurnResponse;
import fruition.agent.service.AgentTurnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@Tag(name = "Agent", description = "워크스페이스 문맥에서 Agent 요청을 실행하는 API")
public class AgentTurnController {

    private final AgentTurnService agentTurnService;

    public AgentTurnController(AgentTurnService agentTurnService) {
        this.agentTurnService = agentTurnService;
    }

    @Operation(
            summary = "Agent turn 실행",
            description = "사용자 요청과 선택한 문서 문맥을 llmPipeline에 전달하고 Agent의 응답과 실행 계획을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Agent 응답 생성 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청"),
        @ApiResponse(responseCode = "404", description = "워크스페이스 또는 참조 문서를 찾을 수 없음"),
        @ApiResponse(responseCode = "502", description = "llmPipeline 요청 실패"),
        @ApiResponse(responseCode = "503", description = "llmPipeline 연결 시간 초과 또는 사용 불가")
    })
    @PostMapping("/turn")
    public ResponseEntity<AgentTurnResponse> turn(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AgentTurnRequest request) {
        return ResponseEntity.ok(agentTurnService.turn(workspaceId, userId, request));
    }
}

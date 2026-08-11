package fruition.core.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.service.AgentTurnService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/agent")
@Tag(name = "Agent", description = "워크스페이스 문맥에서 Agent 실행을 관리하는 API")
public class AgentTurnController {

    private final AgentTurnService agentTurnService;

    public AgentTurnController(AgentTurnService agentTurnService) {
        this.agentTurnService = agentTurnService;
    }

    @Operation(summary = "Agent turn 실행", description = "사용자 요청을 비동기 Agent 실행 대기열에 등록합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Agent 실행이 대기열에 등록됨",
            content = @Content(schema = @Schema(implementation = AgentTurnResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "423", description = "다른 사용자가 문서를 편집 중",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/turn")
    public ResponseEntity<AgentTurnResponse> turn(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AgentTurnRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(agentTurnService.turn(workspaceId, userId, request));
    }

    @Operation(summary = "Agent turn 결과 조회", description = "워크스페이스의 Agent 실행 결과를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "결과 조회 성공",
            content = @Content(schema = @Schema(implementation = AgentTurnResponse.class))),
        @ApiResponse(responseCode = "400", description = "Agent run ID 형식이 올바르지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "실행 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Agent 상태 파이프라인 사용 불가",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class})))
    })
    @GetMapping("/turn/{run_id}")
    public ResponseEntity<AgentTurnResponse> getTurn(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "조회할 Agent 실행 ID", required = true)
            @PathVariable("run_id") String runId) {
        return ResponseEntity.ok(agentTurnService.get(workspaceId, userId, runId));
    }
}

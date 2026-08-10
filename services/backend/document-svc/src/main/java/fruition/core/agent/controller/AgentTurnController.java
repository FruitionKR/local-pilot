package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.service.AgentTurnService;
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
public class AgentTurnController {

    private final AgentTurnService agentTurnService;

    public AgentTurnController(AgentTurnService agentTurnService) {
        this.agentTurnService = agentTurnService;
    }

    @PostMapping("/turn")
    public ResponseEntity<AgentTurnResponse> turn(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody AgentTurnRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(agentTurnService.turn(workspaceId, userId, request));
    }

    @GetMapping("/turn/{run_id}")
    public ResponseEntity<AgentTurnResponse> getTurn(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("run_id") String runId) {
        return ResponseEntity.ok(agentTurnService.get(workspaceId, userId, runId));
    }
}

package fruition.agent.controller;

import fruition.agent.dto.AgentToolExecuteRequest;
import fruition.agent.dto.AgentToolReadRequest;
import fruition.agent.service.AgentServiceTokenVerifier;
import fruition.agent.service.AgentToolService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/agent/tools")
public class AgentToolController {
    private final AgentServiceTokenVerifier tokenVerifier;
    private final AgentToolService toolService;

    public AgentToolController(AgentServiceTokenVerifier tokenVerifier, AgentToolService toolService) {
        this.tokenVerifier = tokenVerifier;
        this.toolService = toolService;
    }

    @PostMapping("/read/{tool_name}")
    public ResponseEntity<Object> read(
            @PathVariable("tool_name") String toolName,
            @RequestHeader(value = "X-Agent-Service-Token", required = false) String token,
            @Valid @RequestBody AgentToolReadRequest request) {
        tokenVerifier.verify(token);
        return ResponseEntity.ok(toolService.read(toolName, request));
    }

    @PostMapping("/execute/{tool_name}")
    public ResponseEntity<Object> execute(
            @PathVariable("tool_name") String toolName,
            @RequestHeader(value = "X-Agent-Service-Token", required = false) String token,
            @Valid @RequestBody AgentToolExecuteRequest request) {
        tokenVerifier.verify(token);
        return ResponseEntity.ok(toolService.execute(toolName, request));
    }
}

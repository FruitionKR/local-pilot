package fruition.access.workspace.controller;

import fruition.access.workspace.service.WorkspaceAiModelService;
import fruition.shared.util.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import fruition.access.workspace.dto.WorkspaceAiModelRequest;
import jakarta.validation.Valid;

@RestController
public class InternalWorkspaceAiModelController {
    private final WorkspaceAiModelService service;
    private final String internalToken;

    public InternalWorkspaceAiModelController(
            WorkspaceAiModelService service,
            @Value("${app.internal.callback-token}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @GetMapping("/internal/workspaces/{workspace_id}/ai-model-settings")
    public ResponseEntity<?> get(
            @PathVariable("workspace_id") String workspaceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
        }
        return ResponseEntity.ok(service.getInternal(workspaceId));
    }

    @PutMapping("/internal/workspaces/{workspace_id}/ai-model-settings")
    public ResponseEntity<?> update(
            @PathVariable("workspace_id") String workspaceId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody WorkspaceAiModelRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
        }
        return ResponseEntity.ok(service.updateInternal(workspaceId, request));
    }

    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }
}

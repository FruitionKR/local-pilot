package fruition.core.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.shared.ai.AiModelCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/ai-model-settings")
public class WorkspaceAiModelSettingsController {
    private final WorkspaceAccessGuard accessGuard;
    private final WorkspaceAiModelClient client;
    private final AiModelCatalog catalog;

    public WorkspaceAiModelSettingsController(WorkspaceAccessGuard accessGuard,
                                              WorkspaceAiModelClient client,
                                              AiModelCatalog catalog) {
        this.accessGuard = accessGuard;
        this.client = client;
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<SettingsResponse> get(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId) {
        accessGuard.requireMember(workspaceId, userId);
        return ResponseEntity.ok(new SettingsResponse(client.get(workspaceId)));
    }

    @PutMapping
    public ResponseEntity<SettingsResponse> update(
            @AuthenticationPrincipal String userId,
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody SettingsRequest request) {
        String role = accessGuard.getRole(workspaceId, userId);
        if ("NONE".equals(role)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        if (!"OWNER".equals(role)) {
            throw new WorkspaceAiModelForbiddenException();
        }
        AiModelCatalog.AiModel selected = catalog.resolve(
                request.ingestLint().provider(), request.ingestLint().model());
        return ResponseEntity.ok(new SettingsResponse(
                client.update(workspaceId, selected.provider(), selected.model())));
    }

    public record SettingsRequest(
            @JsonProperty("ingest_lint") @NotNull @Valid AiModelSelection ingestLint) {}
    public record AiModelSelection(@NotBlank String provider, @NotBlank String model) {}
    public record SettingsResponse(
            @JsonProperty("ingest_lint") WorkspaceAiModelClient.AiModelSelection ingestLint) {}
}

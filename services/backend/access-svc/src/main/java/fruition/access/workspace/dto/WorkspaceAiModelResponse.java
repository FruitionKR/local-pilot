package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.Workspace;

public record WorkspaceAiModelResponse(@JsonProperty("ingest_lint") AiModelSelection ingestLint) {
    public static WorkspaceAiModelResponse from(Workspace workspace) {
        return new WorkspaceAiModelResponse(new AiModelSelection(
                workspace.getIngestLintProvider(), workspace.getIngestLintModel()));
    }

    public record AiModelSelection(String provider, String model) {}
}

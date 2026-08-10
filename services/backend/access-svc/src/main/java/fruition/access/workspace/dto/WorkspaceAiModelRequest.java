package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkspaceAiModelRequest(
        @JsonProperty("ingest_lint") @NotNull @Valid AiModelSelection ingestLint) {
    public record AiModelSelection(
            @NotBlank String provider,
            @NotBlank String model
    ) {}
}

package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.Workspace;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "워크스페이스 AI 모델 설정. 기본값은 openai + gpt-5-nano다.")
public record WorkspaceAiModelResponse(
        @JsonProperty("ingest_lint")
        @Schema(description = "ingest·lint 작업에 쓰는 provider/model 조합")
        AiModelSelection ingestLint) {

    public static WorkspaceAiModelResponse from(Workspace workspace) {
        return new WorkspaceAiModelResponse(new AiModelSelection(
                workspace.getIngestLintProvider(), workspace.getIngestLintModel()));
    }

    // WorkspaceAiModelRequest의 동명 record와 겹치지 않도록 스키마 이름을 분리한다.
    @Schema(name = "AiModelSelectionResponse", description = "현재 설정된 provider와 model 조합")
    public record AiModelSelection(
            @Schema(description = "LLM provider", allowableValues = {"openai", "gemini", "claude"},
                    example = "openai")
            String provider,

            @Schema(description = "모델명", example = "gpt-5-nano")
            String model) {}
}

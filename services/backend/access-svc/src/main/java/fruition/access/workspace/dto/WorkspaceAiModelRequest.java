package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "워크스페이스 AI 모델 설정 변경 요청. OWNER만 호출할 수 있다.")
public record WorkspaceAiModelRequest(
        @JsonProperty("ingest_lint") @NotNull @Valid
        @Schema(description = "ingest·lint 작업에 쓸 provider/model 조합")
        AiModelSelection ingestLint) {

    public record AiModelSelection(
            @NotBlank
            @Schema(description = "LLM provider. 활성 model catalog에 있는 조합만 허용된다.",
                    allowableValues = {"openai", "gemini", "claude"}, example = "openai")
            String provider,

            @NotBlank
            @Schema(description = "provider와 짝이 맞는 모델명", example = "gpt-5-nano")
            String model
    ) {}
}

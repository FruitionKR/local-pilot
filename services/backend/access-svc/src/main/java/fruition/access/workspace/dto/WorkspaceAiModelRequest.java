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

    // WorkspaceAiModelResponse의 동명 record와 단순 이름이 겹쳐 명세에서 한쪽이 덮인다. 요청은
    // 필수 제약이 있고 응답은 없으므로 합쳐지면 계약이 틀어진다 — 스키마 이름을 분리한다.
    @Schema(name = "AiModelSelectionRequest",
            description = "설정할 provider와 model 조합. 활성 catalog에 있는 짝만 허용된다.")
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

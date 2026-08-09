package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SkillDraftRequest(
        @Pattern(regexp = "^$|^[a-z0-9][a-z0-9-]{0,62}$", message = "command는 소문자 영문, 숫자, 하이픈만 사용할 수 있습니다.")
        String command,
        @NotBlank(message = "이름을 입력해야 합니다.")
        @Size(max = 63, message = "이름은 63자 이하여야 합니다.")
        String name,
        @NotBlank(message = "지시사항을 입력해야 합니다.")
        @Size(max = 30000, message = "지시사항은 30,000자 이하여야 합니다.")
        String instructions,
        @NotBlank(message = "저장 범위를 선택해야 합니다.")
        @Pattern(regexp = "personal|team", message = "저장 범위는 personal 또는 team이어야 합니다.")
        String scope,
        @JsonProperty("reference_document_ids")
        @Size(max = 3, message = "참조 문서는 최대 3개까지 선택할 수 있습니다.")
        List<@NotBlank(message = "참조 문서 ID는 비어 있을 수 없습니다.") String> referenceDocumentIds,
        String description,
        List<String> capabilities,
        @JsonProperty("allowed_tools") List<String> allowedTools
) {
    public SkillDraftRequest {
        referenceDocumentIds = referenceDocumentIds == null ? List.of() : List.copyOf(referenceDocumentIds);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}

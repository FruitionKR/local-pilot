package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * @param previewToken 미리보기 응답에서 받은 값. 그사이 대상이 바뀌면 409로 거절한다
 */
public record RestoreExecuteRequest(
        @JsonProperty("preview_token") @NotBlank String previewToken
) {}

package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WorkspaceIconUpdateRequest(
        @JsonProperty("icon_emoji")
        @Size(max = 32, message = "icon_emoji는 32자 이하여야 합니다.")
        // 공백이 없는 한 덩어리만 받는다. 이모지 자체를 정규식으로 판별하지는 않는다 —
        // 유니코드 개정마다 표가 늘어 유지비가 크고, 자기 워크스페이스 아이콘이라 위험도 낮다.
        @Pattern(regexp = "\\S+", message = "icon_emoji에는 공백을 포함할 수 없습니다.")
        @Schema(description = "아이콘으로 쓸 이모지. null을 주면 아이콘을 지운다.",
                example = "📁", nullable = true)
        String iconEmoji
) {}

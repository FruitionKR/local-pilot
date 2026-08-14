package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 채팅 Wiki page화 export 요청.
 *
 * @param selectionMode "full"(세션 전체) 또는 "partial"(선택 문답)
 * @param pairIds       partial일 때 위키화할 문답(pair) id 목록. full이면 무시된다.
 */
@Schema(description = "채팅 내용을 Wiki 페이지로 내보내는 요청")
public record ChatWikiExportRequest(
        @JsonProperty("selection_mode")
        @Schema(description = "full은 세션 전체, partial은 선택한 문답만 내보낸다.",
                allowableValues = {"full", "partial"}, example = "full")
        String selectionMode,

        @JsonProperty("pair_ids")
        @Schema(description = "partial일 때 내보낼 문답(pair) ID 목록. full이면 무시된다.")
        List<String> pairIds
) {
}

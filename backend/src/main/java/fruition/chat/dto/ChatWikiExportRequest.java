package fruition.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 채팅 Wiki page화 export 요청.
 *
 * @param selectionMode "full"(세션 전체) 또는 "partial"(선택 문답)
 * @param pairIds       partial일 때 위키화할 문답(pair) id 목록. full이면 무시된다.
 */
public record ChatWikiExportRequest(
        @JsonProperty("selection_mode") String selectionMode,
        @JsonProperty("pair_ids") List<String> pairIds
) {
}

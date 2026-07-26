package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 문서를 폴더로 배치한다. folder_id가 null이면 최상위 배치, sort_order 생략 시 대상 위치의 마지막. */
public record DocumentPlacementRequest(
        @JsonProperty("folder_id") UUID folderId,
        @JsonProperty("sort_order") Long sortOrder,
        @NotNull @Min(1) @JsonProperty("base_version") Long baseVersion
) {
}

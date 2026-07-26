package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 폴더 이름·상위 폴더·정렬을 함께 갱신한다. sort_order를 생략하면 새 부모의 마지막으로 배치한다. */
public record FolderUpdateRequest(
        @NotBlank String name,
        @JsonProperty("parent_folder_id") UUID parentFolderId,
        @JsonProperty("sort_order") Long sortOrder,
        @NotNull @Min(1) @JsonProperty("base_version") Long baseVersion
) {
}

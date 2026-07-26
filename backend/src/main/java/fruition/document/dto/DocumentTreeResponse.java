package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/** 폴더 계층과 문서 배치를 함께 반환한다. 클라이언트가 parent 참조로 트리를 구성한다. */
public record DocumentTreeResponse(
        List<FolderResponse> folders,
        List<Item> documents
) {
    public record Item(
            String id,
            String filename,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("file_type") String fileType,
            boolean editable,
            @JsonProperty("source_folder_id") UUID sourceFolderId,
            @JsonProperty("sort_order") long sortOrder,
            @JsonProperty("current_version") long currentVersion
    ) {}
}

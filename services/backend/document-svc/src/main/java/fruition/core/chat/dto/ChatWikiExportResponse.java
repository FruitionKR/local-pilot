package fruition.core.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 채팅 Wiki page화 export 응답.
 *
 * @param exportDocumentId 생성(또는 중복 시 기존) export 문서 id
 * @param status           "processing"(새로 등록) 또는 "skipped"(동일 content 이미 존재)
 */
@Schema(description = "채팅 Wiki export 접수 결과. 실제 처리는 비동기다.")
public record ChatWikiExportResponse(
        @Schema(description = "생성된 export 문서 ID. 같은 내용이 이미 있으면 기존 문서 ID다.",
                example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String exportDocumentId,

        @Schema(description = "processing은 새로 등록, skipped는 같은 내용이 이미 있어 건너뜀을 뜻한다.",
                allowableValues = {"processing", "skipped"}, example = "processing")
        String status) {
}

package fruition.core.chat.dto;

/**
 * 채팅 Wiki page화 export 응답.
 *
 * @param exportDocumentId 생성(또는 중복 시 기존) export 문서 id
 * @param status           "processing"(새로 등록) 또는 "skipped"(동일 content 이미 존재)
 */
public record ChatWikiExportResponse(String exportDocumentId, String status) {
}

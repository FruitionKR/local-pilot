package fruition.core.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 근거(evidence) 하나가 참조하는 문서·블록 쌍. 하나의 rank가 여러 문서의 block을 참조할 수 있어,
 * 대표 문서만 표현하는 legacy {@code source_document_id}/{@code source_block_ids}와 달리 전역 ref를 구조화한다.
 */
public record SourceRef(
        @JsonProperty("source_document_id") String sourceDocumentId,
        @JsonProperty("source_block_id") String sourceBlockId
) {}

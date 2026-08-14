package fruition.core.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 근거(evidence) 하나가 참조하는 문서·블록 쌍. 하나의 rank가 여러 문서의 block을 참조할 수 있어,
 * 대표 문서만 표현하는 legacy {@code source_document_id}/{@code source_block_ids}와 달리 전역 ref를 구조화한다.
 */
@Schema(description = "근거가 가리키는 문서·block 쌍. 한 근거가 여러 문서를 참조할 수 있어 쌍으로 표현한다.")
public record SourceRef(
        @JsonProperty("source_document_id")
        @Schema(description = "근거가 있는 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String sourceDocumentId,

        @JsonProperty("source_block_id")
        @Schema(description = "문서 안에서 근거가 있는 block ID")
        String sourceBlockId
) {}

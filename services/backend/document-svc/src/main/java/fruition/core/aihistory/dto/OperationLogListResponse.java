package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.aihistory.domain.OperationLog;

import java.time.Instant;
import java.util.List;

/**
 * AI 작업 로그 목록. {@code ai_operation_logs}만 읽으며 diff를 계산하지 않는다.
 *
 * @param nextCursor 다음 페이지 기준. null이면 마지막이다
 */
public record OperationLogListResponse(
        List<Item> logs,
        @JsonProperty("next_cursor") String nextCursor
) {

    public record Item(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("operation_type") String operationType,
            String status,
            @JsonProperty("target_document_id") String targetDocumentId,
            String summary,
            @JsonProperty("changed_resource_count") int changedResourceCount,
            @JsonProperty("restored_from") String restoredFrom,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("completed_at") Instant completedAt
    ) {
        public static Item from(OperationLog log) {
            return new Item(
                    log.getOperationId(),
                    log.getOperationType().name(),
                    log.getStatus().name(),
                    log.getTargetDocumentId(),
                    log.getSummary(),
                    log.getChangedResourceCount(),
                    log.getRestoredFrom(),
                    log.getCreatedAt(),
                    log.getCompletedAt());
        }
    }
}

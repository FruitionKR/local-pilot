package fruition.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;

import java.time.Instant;
import java.util.List;

/**
 * 작업 상세. 그 작업이 바꾼 리소스를 함께 반환한다.
 *
 * <p>{@code additions}·{@code deletions}는 저장 시점에 계산해 둔 값이라 여기서 diff를 돌리지 않는다.
 * 실제 변경 내용은 사용자가 펼칠 때 페이지 diff 엔드포인트로 따로 가져간다.
 */
public record OperationLogDetailResponse(
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("operation_type") String operationType,
        String status,
        @JsonProperty("target_document_id") String targetDocumentId,
        String summary,
        @JsonProperty("changed_resource_count") int changedResourceCount,
        @JsonProperty("restored_from") String restoredFrom,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("completed_at") Instant completedAt,
        List<Change> changes
) {

    /**
     * @param beforeRevision 손대기 직전 버전. null이면 새로 만든 것
     * @param afterRevision  이 작업이 만든 버전. 위임·실패면 null
     */
    public record Change(
            long id,
            @JsonProperty("resource_type") String resourceType,
            @JsonProperty("resource_id") String resourceId,
            @JsonProperty("before_revision") Long beforeRevision,
            @JsonProperty("after_revision") Long afterRevision,
            @JsonProperty("change_type") String changeType,
            @JsonProperty("change_summary") String changeSummary,
            Integer additions,
            Integer deletions
    ) {
        public static Change from(OperationChange change) {
            return new Change(
                    change.getId(),
                    change.getResourceType().name(),
                    change.getResourceId(),
                    change.getBeforeRevision(),
                    change.getAfterRevision(),
                    change.getChangeType().name(),
                    change.getChangeSummary(),
                    change.getAdditions(),
                    change.getDeletions());
        }
    }

    public static OperationLogDetailResponse from(OperationLog log, List<OperationChange> changes) {
        return new OperationLogDetailResponse(
                log.getOperationId(),
                log.getOperationType().name(),
                log.getStatus().name(),
                log.getTargetDocumentId(),
                log.getSummary(),
                log.getChangedResourceCount(),
                log.getRestoredFrom(),
                log.getCreatedAt(),
                log.getCompletedAt(),
                changes.stream().map(Change::from).toList());
    }
}

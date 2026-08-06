package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.aihistory.service.ChangeDiffLoader;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;

import java.time.Instant;
import java.util.List;

/**
 * 작업 상세. 그 작업이 바꾼 리소스를 함께 반환한다.
 *
 * <p>{@code additions}·{@code deletions}는 저장 시점에 계산해 둔 값이라 다시 세지 않는다.
 * {@code hunks}는 저장된 본문 두 벌을 읽어 조회 시점에 계산한다.
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
     * @param hunks          실제 변경분. 비교할 짝이 없거나 계산이 거부되면 생략된다
     * @param diffTooLarge   두 본문 차이가 너무 커서 계산하지 못한 경우. 개별 diff로도 볼 수 없다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(
            long id,
            @JsonProperty("resource_type") String resourceType,
            @JsonProperty("resource_id") String resourceId,
            @JsonProperty("before_revision") Long beforeRevision,
            @JsonProperty("after_revision") Long afterRevision,
            @JsonProperty("change_type") String changeType,
            @JsonProperty("change_summary") String changeSummary,
            Integer additions,
            Integer deletions,
            List<DocumentContentDiffResponse.Hunk> hunks,
            @JsonProperty("diff_too_large") Boolean diffTooLarge
    ) {
        public static Change from(OperationChange change, ChangeDiffLoader.Diff diff) {
            return new Change(
                    change.getId(),
                    change.getResourceType().name(),
                    change.getResourceId(),
                    change.getBeforeRevision(),
                    change.getAfterRevision(),
                    change.getChangeType().name(),
                    change.getChangeSummary(),
                    change.getAdditions(),
                    change.getDeletions(),
                    diff.hunks(),
                    diff.tooLarge() ? Boolean.TRUE : null);
        }
    }

    public static OperationLogDetailResponse from(OperationLog log, List<Change> changes) {
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
                changes);
    }
}

package fruition.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 복구 실행 결과.
 *
 * @param operationId  이번 복구 작업 식별자. 진행 상황을 이 id로 조회한다
 * @param restoredFrom 되돌린 대상 작업
 * @param rebuilding   재작성이 진행 중인지. false면 이 시점에 복구가 끝났다
 */
public record RestoreExecuteResponse(
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("restored_from") String restoredFrom,
        @JsonProperty("delete_count") int deleteCount,
        @JsonProperty("restore_count") int restoreCount,
        @JsonProperty("rebuild_count") int rebuildCount,
        boolean rebuilding,
        String status
) {
    public static RestoreExecuteResponse from(String operationId, String restoredFrom,
                                              RestorePlan plan, boolean notified) {
        String status = !notified ? "notify_pending" : (plan.hasRebuild() ? "rebuilding" : "succeeded");
        return new RestoreExecuteResponse(operationId, restoredFrom,
                plan.deleteCount(), plan.restoreCount(), plan.rebuildCount(),
                plan.hasRebuild(), status);
    }
}

package fruition.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 복구 실행 결과.
 *
 * @param operationId  이번 복구 작업 식별자. 진행 상황을 이 id로 조회한다
 * @param restoredFrom 되돌린 대상 작업
 * @param rebuilding   llmPipeline 결과를 기다리는 중인지. false면 통지가 실패해 보류된 것이다
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
    /** 문서 편집 되돌리기. 재작성이 없어 이 시점에 이미 끝나 있다. */
    public static RestoreExecuteResponse forDocument(String operationId, String restoredFrom) {
        return new RestoreExecuteResponse(operationId, restoredFrom, 0, 1, 0, false, "succeeded");
    }

    public static RestoreExecuteResponse from(String operationId, String restoredFrom,
                                              RestorePlan plan, boolean notified) {
        // 재작성 대상이 없어도 llmPipeline이 링크·임베딩을 정리하고 결과를 보내온다.
        // 확정은 그 결과를 받을 때 하므로 여기서는 완료로 답하지 않는다.
        String status = notified ? "rebuilding" : "notify_pending";
        return new RestoreExecuteResponse(operationId, restoredFrom,
                plan.deleteCount(), plan.restoreCount(), plan.rebuildCount(),
                notified, status);
    }
}

package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 복구 미리보기 응답. 되돌리기는 무를 수 없으므로 무엇이 삭제·복원·재작성되는지 먼저 보여준다.
 *
 * <p>Wiki 되돌리기면 {@code pages}가 차고, 문서 편집 되돌리기면 {@code document}가 찬다.
 * 둘이 동시에 차는 경우는 없다.
 *
 * @param previewToken 실행할 때 그대로 돌려줘야 한다. 그사이 대상이 바뀌면 409로 거절한다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestorePreviewResponse(
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("delete_count") int deleteCount,
        @JsonProperty("restore_count") int restoreCount,
        @JsonProperty("rebuild_count") int rebuildCount,
        List<Page> pages,
        DocumentRestorePlan document,
        @JsonProperty("preview_token") String previewToken
) {

    /**
     * @param action             delete · restore · rebuild
     * @param targetRevision     복원일 때 되돌릴 revision. 그 외에는 null
     * @param contributionCount  복구 후 남는 기여 수. 삭제면 0
     */
    public record Page(
            @JsonProperty("page_id") String pageId,
            String action,
            @JsonProperty("target_revision") Long targetRevision,
            @JsonProperty("contribution_count") int contributionCount
    ) {}

    public static RestorePreviewResponse from(String operationId, RestorePlan plan, String previewToken) {
        List<Page> pages = plan.pages().stream()
                .map(p -> new Page(p.pageId(), p.action().name(), p.targetRevision(), p.contributionCount()))
                .toList();
        return new RestorePreviewResponse(operationId,
                plan.deleteCount(), plan.restoreCount(), plan.rebuildCount(), pages, null, previewToken);
    }

    /** 문서 편집 되돌리기. 페이지 목록이 비고 문서 계획만 담긴다. */
    public static RestorePreviewResponse from(String operationId, DocumentRestorePlan plan,
                                              String previewToken) {
        return new RestorePreviewResponse(operationId, 0, 1, 0, List.of(), plan, previewToken);
    }
}

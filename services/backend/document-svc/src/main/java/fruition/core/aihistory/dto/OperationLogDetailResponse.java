package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.service.ChangeDiffLoader;
import fruition.core.document.dto.DocumentContentDiffResponse;

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
        List<Change> changes,
        @JsonInclude(JsonInclude.Include.NON_NULL) RestoreSummary restore
) {

    /** 기존 호출부를 위한 생성자. 복구가 아니면 restore는 응답에서 생략된다. */
    public OperationLogDetailResponse(String operationId, String operationType, String status,
                                      String targetDocumentId, String summary,
                                      int changedResourceCount, String restoredFrom,
                                      Instant createdAt, Instant completedAt,
                                      List<Change> changes) {
        this(operationId, operationType, status, targetDocumentId, summary,
                changedResourceCount, restoredFrom, createdAt, completedAt, changes, null);
    }

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

    /** 복구 지시서와 실제 감사 변경분 중 사용자에게 필요한 값만 노출한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RestoreSummary(
            RestorePlanSummary plan,
            RestoreResult result
    ) {
        public static RestoreSummary from(OperationLog log, List<Change> changes,
                                          ObjectMapper objectMapper) {
            RestorePlanSummary plan = readPlan(log.getRestoreManifest(), objectMapper);
            if (plan == null) {
                plan = RestorePlanSummary.fromChanges(changes);
            }
            return new RestoreSummary(plan, RestoreResult.from(changes));
        }

        private static RestorePlanSummary readPlan(String manifest, ObjectMapper objectMapper) {
            if (manifest == null || objectMapper == null) {
                return null;
            }
            try {
                JsonNode root = objectMapper.readTree(manifest);
                JsonNode plan = root.has("plan") ? root.get("plan") : root;
                RestorePlan value = objectMapper.treeToValue(plan, RestorePlan.class);
                return RestorePlanSummary.from(value);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public record RestorePlanSummary(
            @JsonProperty("delete_count") int deleteCount,
            @JsonProperty("restore_count") int restoreCount,
            @JsonProperty("rebuild_count") int rebuildCount,
            List<PlanPage> pages
    ) {
        static RestorePlanSummary from(RestorePlan plan) {
            List<PlanPage> pages = plan.pages().stream()
                    .map(page -> new PlanPage(page.pageId(), page.action().name(),
                            page.contributionCount()))
                    .toList();
            return new RestorePlanSummary(plan.deleteCount(), plan.restoreCount(),
                    plan.rebuildCount(), pages);
        }

        static RestorePlanSummary fromChanges(List<Change> changes) {
            int delete = count(changes, ResourceType.wiki_page, ChangeType.deleted);
            int restore = count(changes, ResourceType.wiki_page, ChangeType.restored);
            int rebuild = count(changes, ResourceType.wiki_page, ChangeType.delegated);
            List<PlanPage> pages = changes.stream()
                    .filter(change -> change.resourceType().equals(ResourceType.wiki_page.name()))
                    .filter(change -> change.changeType().equals(ChangeType.deleted.name())
                            || change.changeType().equals(ChangeType.restored.name())
                            || change.changeType().equals(ChangeType.delegated.name()))
                    .map(change -> new PlanPage(change.resourceId(),
                            ChangeType.delegated.name().equals(change.changeType())
                                    ? "rebuild" : change.changeType(), null))
                    .toList();
            return new RestorePlanSummary(delete, restore, rebuild, pages);
        }

        private static int count(List<Change> changes, ResourceType resourceType,
                                 ChangeType changeType) {
            return (int) changes.stream()
                    .filter(change -> resourceType.name().equals(change.resourceType()))
                    .filter(change -> changeType.name().equals(change.changeType()))
                    .count();
        }
    }

    public record PlanPage(
            @JsonProperty("page_id") String pageId,
            String action,
            @JsonProperty("contribution_count") Integer contributionCount
    ) {}

    public record RestoreResult(
            @JsonProperty("deleted_count") int deletedCount,
            @JsonProperty("restored_count") int restoredCount,
            @JsonProperty("rebuilt_count") int rebuiltCount,
            @JsonProperty("failed_count") int failedCount,
            @JsonProperty("removed_link_count") int removedLinkCount,
            @JsonProperty("restored_link_count") int restoredLinkCount
    ) {
        static RestoreResult from(List<Change> changes) {
            return new RestoreResult(
                    count(changes, ResourceType.wiki_page, ChangeType.deleted),
                    count(changes, ResourceType.wiki_page, ChangeType.restored),
                    count(changes, ResourceType.wiki_page, ChangeType.rebuilt),
                    countFailed(changes),
                    count(changes, ResourceType.relation_link, ChangeType.link_removed),
                    count(changes, ResourceType.relation_link, ChangeType.link_restored));
        }

        private static int countFailed(List<Change> changes) {
            return (int) changes.stream()
                    .filter(change -> change.changeType().equals(ChangeType.rebuild_failed.name())
                            || change.changeType().equals(ChangeType.action_failed.name()))
                    .map(Change::resourceId)
                    .distinct()
                    .count();
        }

        private static int count(List<Change> changes, ResourceType resourceType,
                                 ChangeType changeType) {
            return (int) changes.stream()
                    .filter(change -> resourceType.name().equals(change.resourceType()))
                    .filter(change -> changeType.name().equals(change.changeType()))
                    .map(Change::resourceId)
                    .distinct()
                    .count();
        }
    }

    public static OperationLogDetailResponse from(OperationLog log, List<Change> changes) {
        return from(log, changes, null);
    }

    public static OperationLogDetailResponse from(OperationLog log, List<Change> changes,
                                                  ObjectMapper objectMapper) {
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
                changes,
                log.getOperationType() == OperationType.restore
                        ? RestoreSummary.from(log, changes, objectMapper) : null);
    }
}

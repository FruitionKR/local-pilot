import type { DocumentProcessingState, NoteEditState, TreeItem } from "../../_lib/types";

type BadgeKind = "processing" | "stalled" | "failed";

// 처리 상태 → 뱃지 문구. stage 세부값은 tooltip으로만 노출한다.
const BADGE_LABEL: Record<BadgeKind, string> = {
  processing: "처리 중",
  stalled: "지연",
  failed: "실패"
};

// status(uploaded/processing/…)와 processing_state(starting/running/stalled/…)를 합쳐
// 뱃지 종류를 정한다. 우선순위: failed > stalled > processing.
function resolveBadgeKind(
  status: TreeItem["status"],
  processingState: DocumentProcessingState | undefined
): BadgeKind | null {
  if (status === "failed" || processingState === "failed") return "failed";
  if (processingState === "stalled") return "stalled";
  if (
    status === "uploading" || status === "uploaded" || status === "processing"
    || processingState === "starting" || processingState === "running"
  ) return "processing";
  return null;
}

function badgeTitle(kind: BadgeKind, processingStage?: string, errorMessage?: string): string | undefined {
  if (kind === "failed") return errorMessage ?? "처리에 실패했습니다.";
  if (kind === "stalled") return processingStage ? `처리 지연: ${processingStage}` : "처리가 지연되고 있습니다.";
  return processingStage || undefined;
}

/** 문서 처리 진행 뱃지(처리 중/지연/실패) + 로컬 편집 상태 점을 표시한다. */
export function TreeNodeStatus({
  status,
  processingState,
  processingStage,
  errorMessage,
  editState
}: {
  status: TreeItem["status"];
  processingState?: DocumentProcessingState;
  processingStage?: string;
  errorMessage?: string;
  editState?: NoteEditState;
}) {
  const badgeKind = resolveBadgeKind(status, processingState);
  const localStatus = editState?.saveStatus === "error" || editState?.saveStatus === "conflict"
    ? "error"
    : editState && (editState.saveStatus !== "saved" || editState.needsReview)
      ? "changed"
      : null;
  if (!badgeKind && !localStatus) return null;

  return (
    <>
      {badgeKind && (
        <small className={`tree-status ${badgeKind}`} title={badgeTitle(badgeKind, processingStage, errorMessage)}>
          {BADGE_LABEL[badgeKind]}
        </small>
      )}
      {localStatus && (
        <small
          className={`tree-edit-status is-${localStatus}`}
          aria-label={localStatus === "error" ? "저장 문제 있음" : "수정 사항 있음"}
          title={localStatus === "error" ? "저장 문제 있음" : "수정 사항 또는 AI 점검 대기"}
        />
      )}
    </>
  );
}

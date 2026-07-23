import type { NoteEditState, TreeItem } from "../../_lib/types";

/** 처리 중(업로드/처리) 파일은 시안처럼 노란 "Modify ⋯" 태그, 실패는 failed 태그를 표시한다. */
export function TreeNodeStatus({
  status,
  editState
}: {
  status: TreeItem["status"];
  editState?: NoteEditState;
}) {
  const isPending = status === "processing" || status === "uploading" || status === "uploaded";
  const localStatus = editState?.saveStatus === "error" || editState?.saveStatus === "conflict"
    ? "error"
    : editState && (editState.saveStatus !== "saved" || editState.needsReview)
      ? "changed"
      : null;
  if ((!status || status === "completed") && !localStatus) return null;

  return (
    <>
      {status && status !== "completed" && (
        <small className={`tree-status ${status}`}>
          {isPending ? "Modify ⋯" : status}
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

import type { TreeItem } from "../../_lib/types";

/** 처리 중(업로드/처리) 파일은 시안처럼 노란 "Modify ⋯" 태그, 실패는 failed 태그를 표시한다. */
export function TreeNodeStatus({ status }: { status: TreeItem["status"] }) {
  if (!status || status === "completed") return null;

  const isPending = status === "processing" || status === "uploading" || status === "uploaded";

  return (
    <small className={`tree-status ${status}`}>
      {isPending ? "Modify ⋯" : status}
    </small>
  );
}

import type { TreeItem } from "../../_lib/types";

export function TreeNodeStatus({ status }: { status: TreeItem["status"] }) {
  if (!status) return null;

  const isPending = status === "processing" || status === "uploading" || status === "uploaded";

  return (
    <small className={`tree-status ${status}`}>
      {isPending && <i />}
      {isPending ? "" : status}
    </small>
  );
}

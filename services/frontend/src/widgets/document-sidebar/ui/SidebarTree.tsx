import { useEffect, useMemo, useState } from "react";
import type { DropTarget, TreeItem } from "@/entities/tree";
import { TreeNode } from "./TreeNode";
import type { TreeInteractionProps } from "../model/types";

export function SidebarTree({
  items,
  projectId,
  interaction,
  defaultOpenIds = []
}: {
  items: TreeItem[];
  projectId: string;
  /** 트리 상호작용 상태·핸들러 묶음 */
  interaction: TreeInteractionProps;
  defaultOpenIds?: string[];
}) {
  const [openIds, setOpenIds] = useState(() => new Set(defaultOpenIds));
  const generatedFolderIds = useMemo(
    () => items.filter((item) => item.generated && item.children?.length).map((item) => item.id),
    [items]
  );

  useEffect(() => {
    if (generatedFolderIds.length === 0) return;
    setOpenIds((current) => {
      const next = new Set(current);
      generatedFolderIds.forEach((id) => next.add(id));
      return next;
    });
  }, [generatedFolderIds]);

  function toggleNode(id: string) {
    setOpenIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function handleDropItem(target: DropTarget) {
    if (!interaction.draggedItemId) return;
    interaction.onMoveItem(target);
  }

  return (
    <>
      {items.map((item) => (
        <TreeNode
          key={item.id}
          item={item}
          depth={0}
          openIds={openIds}
          onToggle={toggleNode}
          projectId={projectId}
          onDropItem={handleDropItem}
          interaction={interaction}
        />
      ))}
    </>
  );
}

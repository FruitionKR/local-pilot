import { useEffect, useMemo, useState } from "react";
import type { DropTarget, TreeItem } from "../../_lib/types";
import { TreeNode } from "./TreeNode";
import type { TreeInteractionProps } from "./types";

export function SidebarTree({
  items,
  projectId,
  draggedItemId,
  selectedItemId,
  dropTarget,
  fileDropTarget,
  editing,
  noteEditStates,
  onMoveItem,
  onDropFiles,
  onDragStart,
  onDragOverItem,
  onFileDragOver,
  onFileDragLeave,
  onDragEnd,
  onContextMenuItem,
  onSelectGraphNode,
  onEditingChange,
  onCommitEditing,
  onCancelEditing,
  defaultOpenIds = []
}: {
  items: TreeItem[];
  projectId: string;
  defaultOpenIds?: string[];
} & TreeInteractionProps) {
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
    if (!draggedItemId) return;
    onMoveItem(target);
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
          draggedItemId={draggedItemId}
          selectedItemId={selectedItemId}
          dropTarget={dropTarget}
          fileDropTarget={fileDropTarget}
          editing={editing}
          noteEditStates={noteEditStates}
          onDragStart={onDragStart}
          onDragOverItem={onDragOverItem}
          onFileDragOver={onFileDragOver}
          onFileDragLeave={onFileDragLeave}
          onDropItem={handleDropItem}
          onDropFiles={onDropFiles}
          onDragEnd={onDragEnd}
          onContextMenuItem={onContextMenuItem}
          onSelectGraphNode={onSelectGraphNode}
          onEditingChange={onEditingChange}
          onCommitEditing={onCommitEditing}
          onCancelEditing={onCancelEditing}
        />
      ))}
    </>
  );
}

import type { DropTarget, TreeItem } from "../../_lib/types";
import { InlineEditInput } from "./InlineEditInput";
import { TreeNodeIcon } from "./TreeNodeIcon";
import type { TreeInteractionProps } from "./types";
import { useTreeNodeDragDrop } from "./useTreeNodeDragDrop";

export function TreeNode({
  item,
  depth,
  openIds,
  onToggle,
  projectId,
  draggedItemId,
  selectedItemId,
  dropTarget,
  fileDropTarget,
  editing,
  onDragStart,
  onDragOverItem,
  onFileDragOver,
  onFileDragLeave,
  onDropItem,
  onDropFiles,
  onDragEnd,
  onContextMenuItem,
  onSelectGraphNode,
  onEditingChange,
  onCommitEditing,
  onCancelEditing
}: {
  item: TreeItem;
  depth: number;
  openIds: Set<string>;
  onToggle: (id: string) => void;
  projectId: string;
  onDropItem: (target: DropTarget) => void;
} & Omit<TreeInteractionProps, "onMoveItem">) {
  const hasChildren = Boolean(item.children?.length);
  const isOpen = openIds.has(item.id);
  const isDropTarget = dropTarget?.projectId === projectId && dropTarget.targetId === item.id;
  const isFileDropTarget = fileDropTarget?.projectId === projectId && fileDropTarget.folderId === item.id;
  const isEditing = editing?.projectId === projectId && editing.itemId === item.id;
  const {
    canDrag,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop
  } = useTreeNodeDragDrop({
    item,
    projectId,
    onDragStart,
    onDragOverItem,
    onFileDragOver,
    onFileDragLeave,
    onDropItem,
    onDropFiles
  });

  return (
    <>
      <button
        type="button"
        className={[
          "tree-row",
          item.active ? "is-active" : "",
          selectedItemId === item.id ? "is-selected" : "",
          draggedItemId === item.id ? "is-dragging" : "",
          isFileDropTarget ? "is-file-drop-target" : "",
          isDropTarget ? `is-drop-${dropTarget.position}` : ""
        ].filter(Boolean).join(" ")}
        style={{ paddingLeft: 10 + depth * 17 }}
        title={item.errorMessage ?? item.sourceUri}
        aria-expanded={hasChildren ? isOpen : undefined}
        draggable={!isEditing && canDrag}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onDragEnd={onDragEnd}
        onContextMenu={(event) => onContextMenuItem(event, projectId, item.id)}
        onClick={(event) => {
          event.stopPropagation();
          if (!isEditing && (item.graphNodeId || item.documentId)) onSelectGraphNode(item);
          if (!isEditing && hasChildren) onToggle(item.id);
        }}
      >
        <TreeNodeIcon item={item} hasChildren={hasChildren} isOpen={isOpen} />
        {isEditing ? (
          <InlineEditInput
            value={editing.label}
            onChange={onEditingChange}
            onCommit={onCommitEditing}
            onCancel={onCancelEditing}
          />
        ) : (
          <>
            <span>{item.label}</span>
            {isFileDropTarget && <small className="tree-drop-hint">여기에 추가</small>}
          </>
        )}
      </button>
      {hasChildren && isOpen && item.children?.map((child) => (
        <TreeNode
          key={child.id}
          item={child}
          depth={depth + 1}
          openIds={openIds}
          onToggle={onToggle}
          projectId={projectId}
          draggedItemId={draggedItemId}
          selectedItemId={selectedItemId}
          dropTarget={dropTarget}
          fileDropTarget={fileDropTarget}
          editing={editing}
          onDragStart={onDragStart}
          onDragOverItem={onDragOverItem}
          onFileDragOver={onFileDragOver}
          onFileDragLeave={onFileDragLeave}
          onDropItem={onDropItem}
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

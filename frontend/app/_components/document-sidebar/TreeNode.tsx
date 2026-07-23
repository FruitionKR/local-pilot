import { cx } from "../../_lib/classNames";
import { isFileItem } from "../../_lib/tree";
import type { DropTarget, TreeItem } from "../../_lib/types";
import { InlineEditInput } from "./InlineEditInput";
import { TreeNodeIcon } from "./TreeNodeIcon";
import { TreeNodeStatus } from "./TreeNodeStatus";
import type { TreeInteractionProps } from "./types";
import { useTreeNodeDragDrop } from "./useTreeNodeDragDrop";

// 트리 행 들여쓰기: 기본 패딩 + depth당 증가 폭 (px)
const TREE_ROW_BASE_PADDING_PX = 8;
const TREE_ROW_INDENT_PER_DEPTH_PX = 18;

// mime type → 시안의 우측 파일 타입 배지 문구
const MIME_TYPE_BADGES: [pattern: string, label: string][] = [
  ["pdf", "PDF"],
  ["plain", "TXT"]
];

/** 파일 항목의 우측에 표시할 타입 배지 문구를 구한다. 알 수 없으면 null */
function fileTypeBadge(item: TreeItem): string | null {
  if (!isFileItem(item)) return null;

  const mimeType = item.mimeType ?? "";
  const matched = MIME_TYPE_BADGES.find(([pattern]) => mimeType.includes(pattern));
  if (matched) return matched[1];

  const extension = item.label.includes(".") ? item.label.split(".").pop() ?? "" : "";
  const fromExtension = MIME_TYPE_BADGES.find(([, label]) => label.toLowerCase() === extension.toLowerCase());
  return fromExtension ? fromExtension[1] : null;
}

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
  noteEditStates,
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
        className={cx(
          "tree-row",
          item.active && "is-active",
          selectedItemId === item.id && "is-selected",
          draggedItemId === item.id && "is-dragging",
          isFileDropTarget && "is-file-drop-target",
          item.type === "folder" ? "is-folder" : "is-note",
          isDropTarget && `is-drop-${dropTarget.position}`
        )}
        style={{ paddingLeft: TREE_ROW_BASE_PADDING_PX + depth * TREE_ROW_INDENT_PER_DEPTH_PX }}
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
            <TreeNodeStatus
              status={item.status}
              editState={item.documentId ? noteEditStates[item.documentId] : undefined}
            />
            {fileTypeBadge(item) && <small className="tree-type-badge">{fileTypeBadge(item)}</small>}
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
          noteEditStates={noteEditStates}
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

import { cx } from "@/shared/lib/classNames";
import { isFileItem } from "@/entities/tree";
import type { DropTarget, TreeItem } from "@/entities/tree";
import { InlineEditInput } from "./InlineEditInput";
import { TreeNodeIcon } from "./TreeNodeIcon";
import type { TreeInteractionProps } from "../model/types";
import { useTreeNodeDragDrop } from "../lib/useTreeNodeDragDrop";
import styles from "./DocumentSidebar.module.css";

// 트리 행 들여쓰기: 기본 패딩 + depth당 증가 폭 (px)
const TREE_ROW_BASE_PADDING_PX = 8;
const TREE_ROW_INDENT_PER_DEPTH_PX = 18;

// mime type → 시안의 우측 파일 타입 배지 문구
const MIME_TYPE_BADGES: [pattern: string, label: string][] = [
  ["pdf", "PDF"],
  ["plain", "TXT"]
];

/** 실제 파일명은 유지하고 표시할 이름과 우측 확장자를 분리한다. */
function fileDisplay(item: TreeItem) {
  if (!isFileItem(item)) return { name: item.label, badge: null };

  const dotIndex = item.label.lastIndexOf(".");
  if (dotIndex > 0 && dotIndex < item.label.length - 1) {
    return {
      name: item.label.slice(0, dotIndex),
      badge: item.label.slice(dotIndex + 1).toUpperCase()
    };
  }

  const mimeType = item.mimeType ?? "";
  const matched = MIME_TYPE_BADGES.find(([pattern]) => mimeType.includes(pattern));
  return { name: item.label, badge: matched?.[1] ?? null };
}

export function TreeNode({
  item,
  depth,
  openIds,
  onToggle,
  projectId,
  onDropItem,
  interaction
}: {
  item: TreeItem;
  depth: number;
  openIds: Set<string>;
  onToggle: (id: string) => void;
  projectId: string;
  onDropItem: (target: DropTarget) => void;
  /** 트리 상호작용 상태·핸들러 묶음. onMoveItem은 onDropItem으로 감싸서 받는다 */
  interaction: Omit<TreeInteractionProps, "onMoveItem">;
}) {
  const { draggedItemId, dropTarget, fileDropTarget, editing } = interaction;
  const hasChildren = Boolean(item.children?.length);
  const isOpen = openIds.has(item.id);
  const isDropTarget = dropTarget?.projectId === projectId && dropTarget.targetId === item.id;
  const isFileDropTarget = fileDropTarget?.projectId === projectId && fileDropTarget.folderId === item.id;
  const isEditing = editing?.projectId === projectId && editing.itemId === item.id;
  const display = fileDisplay(item);
  const {
    canDrag,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop
  } = useTreeNodeDragDrop({
    item,
    projectId,
    onDragStart: interaction.onDragStart,
    onDragOverItem: interaction.onDragOverItem,
    onFileDragOver: interaction.onFileDragOver,
    onFileDragLeave: interaction.onFileDragLeave,
    onDropItem,
    onDropFiles: interaction.onDropFiles
  });

  return (
    <>
      <button
        type="button"
        className={cx(
          styles["tree-row"],
          item.active && styles["is-active"],
          draggedItemId === item.id && styles["is-dragging"],
          isFileDropTarget && styles["is-file-drop-target"],
          item.type === "folder" ? styles["is-folder"] : styles["is-note"],
          depth > 0 && styles["is-nested"],
          isDropTarget && styles[`is-drop-${dropTarget.position}`]
        )}
        style={{ paddingLeft: TREE_ROW_BASE_PADDING_PX + depth * TREE_ROW_INDENT_PER_DEPTH_PX }}
        title={item.errorMessage ?? item.sourceUri}
        aria-expanded={hasChildren ? isOpen : undefined}
        draggable={!isEditing && canDrag}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onDragEnd={interaction.onDragEnd}
        onContextMenu={(event) => interaction.onContextMenuItem(event, projectId, item.id)}
        onClick={(event) => {
          event.stopPropagation();
          if (!isEditing && (item.graphNodeId || item.documentId)) interaction.onSelectGraphNode(item);
          if (!isEditing && hasChildren) onToggle(item.id);
        }}
      >
        <TreeNodeIcon item={item} hasChildren={hasChildren} isOpen={isOpen} />
        {isEditing ? (
          <InlineEditInput
            value={editing.label}
            onChange={interaction.onEditingChange}
            onCommit={interaction.onCommitEditing}
            onCancel={interaction.onCancelEditing}
          />
        ) : (
          <>
            <span>{display.name}</span>
            {display.badge && <small className={styles["tree-type-badge"]}>{display.badge}</small>}
            {isFileDropTarget && <small className={styles["tree-drop-hint"]}>여기에 추가</small>}
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
          onDropItem={onDropItem}
          interaction={interaction}
        />
      ))}
    </>
  );
}

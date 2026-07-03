import type { DragEvent as ReactDragEvent } from "react";
import type { DropTarget, FileDropTarget, TreeItem } from "../../_lib/types";
import { canDragTreeItem, getDroppedFiles, hasDroppedFiles, isFileItem, isWikiItem } from "../../_lib/tree";
import { isPointerLeavingElement, resolveDropPosition, setLightDragPreview } from "./dragDrop";

export function useTreeNodeDragDrop({
  item,
  projectId,
  onDragStart,
  onDragOverItem,
  onFileDragOver,
  onFileDragLeave,
  onDropItem,
  onDropFiles
}: {
  item: TreeItem;
  projectId: string;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDropItem: (target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
}) {
  const canDrag = canDragTreeItem(item);
  const canNestChildren = !isFileItem(item);

  const wikiKindMimeType = item.wikiKind ? `application/x-wiki-kind-${item.wikiKind}` : null;

  // 같은 wikiKind끼리의 드래그인 경우에만 드롭 대상을 계산한다. ("inside"는 "after"로 정규화)
  function resolveWikiDropTarget(event: ReactDragEvent<HTMLButtonElement>): DropTarget | null {
    const isSameKind = wikiKindMimeType && event.dataTransfer.types.includes(wikiKindMimeType);
    if (!isSameKind) return null;
    const rawPosition = resolveDropPosition(event);
    const position = rawPosition === "inside" ? "after" : rawPosition;
    return { projectId, targetId: item.id, position };
  }

  function handleDragStart(event: ReactDragEvent<HTMLButtonElement>) {
    if (!canDrag) return;
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", item.id);
    if (wikiKindMimeType) event.dataTransfer.setData(wikiKindMimeType, item.id);
    setLightDragPreview(event);
    onDragStart(projectId, item.id);
  }

  function handleDragOver(event: ReactDragEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.dataTransfer.dropEffect = hasDroppedFiles(event) ? "copy" : "move";
    if (hasDroppedFiles(event) && !isFileItem(item) && !isWikiItem(item)) {
      event.stopPropagation();
      onFileDragOver({ projectId, folderId: item.id });
      return;
    }
    if (isWikiItem(item)) {
      const target = resolveWikiDropTarget(event);
      if (target) onDragOverItem(target);
      return;
    }
    if (item.generated) return;
    const position = resolveDropPosition(event);
    onDragOverItem({ projectId, targetId: item.id, position });
  }

  function handleDragLeave(event: ReactDragEvent<HTMLButtonElement>) {
    if (!hasDroppedFiles(event)) return;
    event.stopPropagation();
    if (!isPointerLeavingElement(event)) return;
    onFileDragLeave();
  }

  function handleDrop(event: ReactDragEvent<HTMLButtonElement>) {
    event.preventDefault();
    if (hasDroppedFiles(event)) {
      event.stopPropagation();
      onFileDragLeave();
      onDropFiles(projectId, canNestChildren && !isWikiItem(item) ? item.id : null, getDroppedFiles(event));
      return;
    }
    if (isWikiItem(item)) {
      const target = resolveWikiDropTarget(event);
      if (target) onDropItem(target);
      return;
    }
    if (!item.generated) {
      onDropItem({ projectId, targetId: item.id, position: resolveDropPosition(event) });
    }
  }

  return {
    canDrag,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop
  };
}

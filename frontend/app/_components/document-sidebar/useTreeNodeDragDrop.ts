import type { DragEvent as ReactDragEvent } from "react";
import type { DropTarget, FileDropTarget, TreeItem } from "../../_lib/types";
import { canDragTreeItem, getDroppedFiles, hasDroppedFiles, isFileItem, isWikiItem } from "../../_lib/tree";
import { resolveDropPosition, setLightDragPreview } from "./dragDrop";

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
      const isSameKind = wikiKindMimeType && event.dataTransfer.types.includes(wikiKindMimeType);
      if (isSameKind) {
        const rawPosition = resolveDropPosition(event);
        const position = rawPosition === "inside" ? "after" : rawPosition;
        onDragOverItem({ projectId, targetId: item.id, position });
      }
      return;
    }
    if (item.generated) return;
    const position = resolveDropPosition(event);
    onDragOverItem({ projectId, targetId: item.id, position });
  }

  function handleDragLeave(event: ReactDragEvent<HTMLButtonElement>) {
    if (!hasDroppedFiles(event)) return;
    event.stopPropagation();
    const nextTarget = event.relatedTarget;
    if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
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
      const isSameKind = wikiKindMimeType && event.dataTransfer.types.includes(wikiKindMimeType);
      if (isSameKind) {
        const rawPosition = resolveDropPosition(event);
        const position = rawPosition === "inside" ? "after" : rawPosition;
        onDropItem({ projectId, targetId: item.id, position });
      }
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

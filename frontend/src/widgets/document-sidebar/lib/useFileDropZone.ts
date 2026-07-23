import type { DragEvent as ReactDragEvent } from "react";
import type { FileDropTarget } from "@/entities/tree";
import { getDroppedFiles, hasDroppedFiles } from "@/entities/tree";
import { isPointerLeavingElement } from "./dragDrop";

/**
 * 프로젝트 섹션 루트 영역의 파일 드래그앤드롭 핸들러를 반환합니다.
 * ProjectSection에서 사용하며, 노드별 DnD는 useTreeNodeDragDrop이 담당합니다.
 */
export function useFileDropZone({
  projectId,
  folderId,
  onFileDragOver,
  onFileDragLeave,
  onDropFiles
}: {
  projectId: string;
  folderId: string | null;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
}) {
  function handleDragOver(event: ReactDragEvent<HTMLElement>) {
    if (!hasDroppedFiles(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "copy";
    onFileDragOver({ projectId, folderId });
  }

  function handleDragLeave(event: ReactDragEvent<HTMLElement>) {
    if (!hasDroppedFiles(event)) return;
    if (!isPointerLeavingElement(event)) return;
    onFileDragLeave();
  }

  function handleDrop(event: ReactDragEvent<HTMLElement>) {
    if (!hasDroppedFiles(event)) return;
    event.preventDefault();
    onFileDragLeave();
    onDropFiles(projectId, folderId, getDroppedFiles(event));
  }

  return { handleDragOver, handleDragLeave, handleDrop };
}

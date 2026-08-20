import type { DragEvent as ReactDragEvent } from "react";
import type { Project, TreeItem } from "@/entities/tree/model/tree";

export const initialProjects: Project[] = [
  {
    id: "project-uploaded-documents",
    title: "업로드 문서",
    items: []
  }
];

export function isFileItem(item: TreeItem) {
  return item.type === "file";
}

export function isWikiItem(item: TreeItem) {
  return item.type === "wiki";
}

export function canDragTreeItem(item: TreeItem) {
  if (isWikiItem(item) && item.wikiKind) return true;
  return !item.generated && !isWikiItem(item);
}

export function isSupportedUploadFile(file: File) {
  const name = file.name.toLowerCase();
  return name.endsWith(".pdf") || name.endsWith(".md") || name.endsWith(".txt");
}

// 미지원 파일 필터링은 dropUploadFiles에서 처리한다(거부 안내 모달 표시를 위해 원본 목록 유지).
export function getDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return Array.from(event.dataTransfer.files);
}

export function hasDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return event.dataTransfer.types.includes("Files");
}

export function createClientId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

import type { DragEvent as ReactDragEvent } from "react";
import { makeRawId } from "./graph";
import type { DocumentItemResponse, DocumentUploadResponse, DropPosition, DropTarget, Project, TreeItem, WikiGraphResponse } from "./types";

export const initialProjects: Project[] = [
  {
    id: "project-uploaded-documents",
    title: "업로드 문서",
    items: []
  }
];

function itemContainsId(item: TreeItem, itemId: string): boolean {
  if (item.id === itemId) return true;
  return item.children?.some((child) => itemContainsId(child, itemId)) ?? false;
}

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

// 백엔드 wiki graph에서 자동 생성되는 그룹 폴더 ID
const WIKI_SOURCE_GROUP_ID = "wiki-source-pages";
const WIKI_CONCEPT_GROUP_ID = "wiki-concept-pages";

function isGeneratedGroup(item: TreeItem, groupId: string) {
  return item.generated && item.id === groupId;
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

export function removeTreeItem(items: TreeItem[], itemId: string): { items: TreeItem[]; removed: TreeItem | null } {
  let removed: TreeItem | null = null;
  const nextItems: TreeItem[] = [];

  for (const item of items) {
    if (item.id === itemId) {
      removed = item;
      continue;
    }

    if (item.children?.length) {
      const result = removeTreeItem(item.children, itemId);
      if (result.removed) {
        removed = result.removed;
        nextItems.push({ ...item, children: result.items });
        continue;
      }
    }

    nextItems.push(item);
  }

  return { items: nextItems, removed };
}

/**
 * itemId와 일치하는 항목을 update 결과로 교체한 새 트리를 반환합니다.
 * 재귀 트리 순회 패턴을 한 곳에서 관리합니다.
 */
function mapTreeItemById(items: TreeItem[], itemId: string, update: (item: TreeItem) => TreeItem): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return update(item);
    if (item.children?.length) return { ...item, children: mapTreeItemById(item.children, itemId, update) };
    return item;
  });
}

function replaceTreeItem(items: TreeItem[], itemId: string, replacement: TreeItem): TreeItem[] {
  return mapTreeItemById(items, itemId, () => replacement);
}

function insertTreeItem(items: TreeItem[], movedItem: TreeItem, target: DropTarget): TreeItem[] {
  return items.flatMap((item) => {
    if (item.id === target.targetId) {
      if (target.position === "before") return [movedItem, item];
      if (target.position === "after") return [item, movedItem];
      return [{ ...item, children: [...(item.children ?? []), movedItem] }];
    }

    if (item.children?.length) {
      return [{ ...item, children: insertTreeItem(item.children, movedItem, target) }];
    }

    return [item];
  });
}

export function moveTreeItem(items: TreeItem[], itemId: string, target: DropTarget): TreeItem[] {
  const result = removeTreeItem(items, itemId);
  if (!result.removed) return items;
  if (result.removed.id === target.targetId || itemContainsId(result.removed, target.targetId)) return items;
  return insertTreeItem(result.items, result.removed, target);
}

export function mergeTreeItemsIntoFolder(items: TreeItem[], draggedId: string, targetId: string): TreeItem[] {
  const draggedItem = findTreeItem(items, draggedId);
  const targetItem = findTreeItem(items, targetId);
  if (!draggedItem || !targetItem || !isFileItem(draggedItem) || !isFileItem(targetItem)) return items;

  const result = removeTreeItem(items, draggedId);
  if (!result.removed) return items;

  const folder: TreeItem = {
    id: createClientId("merged-folder"),
    label: "새 문서 묶음",
    type: "folder",
    children: [targetItem, result.removed]
  };

  return replaceTreeItem(result.items, targetId, folder);
}

export function updateTreeItemLabel(items: TreeItem[], itemId: string, label: string): TreeItem[] {
  return mapTreeItemById(items, itemId, (item) => ({ ...item, label, customLabel: true }));
}

export function findTreeItem(items: TreeItem[], itemId: string): TreeItem | null {
  for (const item of items) {
    if (item.id === itemId) return item;
    const found = item.children ? findTreeItem(item.children, itemId) : null;
    if (found) return found;
  }
  return null;
}

export function findTreeItemByGraphNodeId(items: TreeItem[], graphNodeId: string): TreeItem | null {
  for (const item of items) {
    if (item.graphNodeId === graphNodeId) return item;
    const found = item.children ? findTreeItemByGraphNodeId(item.children, graphNodeId) : null;
    if (found) return found;
  }
  return null;
}

export function appendItemsToFolder(items: TreeItem[], folderId: string | null, nextItems: TreeItem[]): TreeItem[] {
  if (!folderId) return [...items, ...nextItems];

  return mapTreeItemById(items, folderId, (item) => ({ ...item, children: [...(item.children ?? []), ...nextItems] }));
}

export function appendFolderToFolder(items: TreeItem[], folderId: string | null, folder: TreeItem): TreeItem[] {
  return appendItemsToFolder(items, folderId, [folder]);
}

export function updateTreeItemStatus(items: TreeItem[], itemId: string, status: TreeItem["status"], errorMessage?: string): TreeItem[] {
  return mapTreeItemById(items, itemId, (item) => ({ ...item, status, errorMessage }));
}

export function applyUploadedDocument(items: TreeItem[], itemId: string, document: DocumentUploadResponse): TreeItem[] {
  return mapTreeItemById(items, itemId, (item) => ({
    ...item,
    label: document.filename,
    status: document.status,
    errorMessage: undefined,
    documentId: document.id,
    graphNodeId: makeRawId(document.id),
    mimeType: document.mime_type,
    byteSize: document.byte_size,
    sourceUri: document.source_uri,
    uploadedAt: document.uploaded_at
  }));
}

function removeGeneratedWikiGroups(items: TreeItem[]): TreeItem[] {
  return items
    .filter((item) => !isGeneratedGroup(item, WIKI_SOURCE_GROUP_ID) && !isGeneratedGroup(item, WIKI_CONCEPT_GROUP_ID))
    .map((item) => item.children?.length ? { ...item, children: removeGeneratedWikiGroups(item.children) } : item);
}

function syncDocumentItems(items: TreeItem[], documents: DocumentItemResponse[]): TreeItem[] {
  const documentById = new Map(documents.map((document) => [document.id, document]));
  return items.map((item) => {
    const document = item.documentId ? documentById.get(item.documentId) : null;
    const nextItem = document ? {
      ...item,
      label: item.customLabel ? item.label : document.filename,
      status: document.status,
      errorMessage: document.error_message,
      mimeType: document.mime_type,
      byteSize: document.byte_size,
      sourceUri: document.source_uri,
      uploadedAt: document.uploaded_at
    } : item;
    if (nextItem.children?.length) return { ...nextItem, children: syncDocumentItems(nextItem.children, documents) };
    return nextItem;
  });
}

function collectDocumentIds(items: TreeItem[], ids = new Set<string>()) {
  for (const item of items) {
    if (item.documentId) ids.add(item.documentId);
    if (item.children?.length) collectDocumentIds(item.children, ids);
  }
  return ids;
}

function areTreeItemsEqual(left: TreeItem[], right: TreeItem[]): boolean {
  if (left.length !== right.length) return false;
  return left.every((leftItem, index) => areTreeItemsShallowEqual(leftItem, right[index]));
}

function areTreeItemsShallowEqual(left: TreeItem, right: TreeItem): boolean {
  return left.id === right.id
    && left.label === right.label
    && left.type === right.type
    && left.wikiKind === right.wikiKind
    && left.generated === right.generated
    && left.customLabel === right.customLabel
    && left.status === right.status
    && left.errorMessage === right.errorMessage
    && left.documentId === right.documentId
    && left.mimeType === right.mimeType
    && left.byteSize === right.byteSize
    && left.sourceUri === right.sourceUri
    && left.uploadedAt === right.uploadedAt
    && left.graphNodeId === right.graphNodeId
    && left.active === right.active
    && areTreeItemsEqual(left.children ?? [], right.children ?? []);
}

export function mergeBackendDataIntoProjects(projects: Project[], documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const knownDocumentIds = collectDocumentIds(projects.flatMap((project) => project.items));
  const missingDocuments = documents.filter((document) => !knownDocumentIds.has(document.id));
  const backendItems = missingDocuments.map((document) => ({
    id: `document-file-${document.id}`,
    label: document.filename,
    type: "file" as const,
    status: document.status,
    documentId: document.id,
    graphNodeId: makeRawId(document.id),
    mimeType: document.mime_type,
    byteSize: document.byte_size,
    sourceUri: document.source_uri,
    uploadedAt: document.uploaded_at,
    errorMessage: document.error_message
  }));
  const nextProjects = projects.map((project, index) => {
    const syncedItems = syncDocumentItems(removeGeneratedWikiGroups(project.items), documents);
    const nextItems = index === 0 ? [...syncedItems, ...backendItems] : syncedItems;
    if (areTreeItemsEqual(project.items, nextItems)) return project;
    return { ...project, items: nextItems };
  });

  return nextProjects.every((project, index) => project === projects[index]) ? projects : nextProjects;
}

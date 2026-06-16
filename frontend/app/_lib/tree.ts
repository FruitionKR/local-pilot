import type { DragEvent as ReactDragEvent } from "react";
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
  return !item.generated && !isWikiItem(item);
}

function isGeneratedGroup(item: TreeItem, groupId: string) {
  return item.generated && item.id === groupId;
}

export function isSupportedUploadFile(file: File) {
  const name = file.name.toLowerCase();
  return name.endsWith(".pdf") || name.endsWith(".md");
}

export function getDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return Array.from(event.dataTransfer.files).filter(isSupportedUploadFile);
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

function replaceTreeItem(items: TreeItem[], itemId: string, replacement: TreeItem): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return replacement;
    if (item.children?.length) return { ...item, children: replaceTreeItem(item.children, itemId, replacement) };
    return item;
  });
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
  return items.map((item) => {
    if (item.id === itemId) return { ...item, label, customLabel: true };
    if (item.children?.length) return { ...item, children: updateTreeItemLabel(item.children, itemId, label) };
    return item;
  });
}

export function findTreeItem(items: TreeItem[], itemId: string): TreeItem | null {
  for (const item of items) {
    if (item.id === itemId) return item;
    const found = item.children ? findTreeItem(item.children, itemId) : null;
    if (found) return found;
  }
  return null;
}

export function appendItemsToFolder(items: TreeItem[], folderId: string | null, nextItems: TreeItem[]): TreeItem[] {
  if (!folderId) return [...items, ...nextItems];

  return items.map((item) => {
    if (item.id === folderId) {
      return { ...item, children: [...(item.children ?? []), ...nextItems] };
    }
    if (item.children?.length) return { ...item, children: appendItemsToFolder(item.children, folderId, nextItems) };
    return item;
  });
}

export function appendFolderToFolder(items: TreeItem[], folderId: string | null, folder: TreeItem): TreeItem[] {
  return appendItemsToFolder(items, folderId, [folder]);
}

export function updateTreeItemStatus(items: TreeItem[], itemId: string, status: TreeItem["status"], errorMessage?: string): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return { ...item, status, errorMessage };
    if (item.children?.length) return { ...item, children: updateTreeItemStatus(item.children, itemId, status, errorMessage) };
    return item;
  });
}

export function applyUploadedDocument(items: TreeItem[], itemId: string, document: DocumentUploadResponse): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) {
      return {
        ...item,
        label: document.filename,
        status: document.status,
        errorMessage: undefined,
        documentId: document.id,
        graphNodeId: `raw:${document.id}`,
        mimeType: document.mime_type,
        byteSize: document.byte_size,
        sourceUri: document.source_uri,
        uploadedAt: document.uploaded_at
      };
    }
    if (item.children?.length) return { ...item, children: applyUploadedDocument(item.children, itemId, document) };
    return item;
  });
}

function removeGeneratedWikiGroups(items: TreeItem[]): TreeItem[] {
  return items
    .filter((item) => !isGeneratedGroup(item, "wiki-source-pages") && !isGeneratedGroup(item, "wiki-concept-pages"))
    .map((item) => item.children?.length ? { ...item, children: removeGeneratedWikiGroups(item.children) } : item);
}

function buildWikiTreeGroups(graph: WikiGraphResponse): TreeItem[] {
  const sourceItems = (graph.nodes ?? [])
    .filter((node) => node.page_type === "source")
    .map((node) => ({
      id: `wiki-item-${node.id}`,
      label: node.title || node.slug || node.id,
      type: "wiki" as const,
      wikiKind: "source" as const,
      graphNodeId: node.id,
      generated: true
    }));

  const conceptItems = (graph.nodes ?? [])
    .filter((node) => node.page_type === "concept")
    .map((node) => ({
      id: `wiki-item-${node.id}`,
      label: node.title || node.slug || node.id,
      type: "wiki" as const,
      wikiKind: "concept" as const,
      graphNodeId: node.id,
      generated: true
    }));

  const groups: TreeItem[] = [];
  if (sourceItems.length > 0) {
    groups.push({
      id: "wiki-source-pages",
      label: "Source 문서",
      type: "folder",
      generated: true,
      children: sourceItems
    });
  }
  if (conceptItems.length > 0) {
    groups.push({
      id: "wiki-concept-pages",
      label: "Concept 문서",
      type: "folder",
      generated: true,
      children: conceptItems
    });
  }
  return groups;
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

export function mergeBackendDataIntoProjects(projects: Project[], documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const knownDocumentIds = collectDocumentIds(projects.flatMap((project) => project.items));
  const missingDocuments = documents.filter((document) => !knownDocumentIds.has(document.id));
  const backendItems = missingDocuments.map((document) => ({
    id: `document-file-${document.id}`,
    label: document.filename,
    type: "file" as const,
    status: document.status,
    documentId: document.id,
    graphNodeId: `raw:${document.id}`,
    mimeType: document.mime_type,
    byteSize: document.byte_size,
    sourceUri: document.source_uri,
    uploadedAt: document.uploaded_at,
    errorMessage: document.error_message
  }));
  const wikiGroups = buildWikiTreeGroups(graph);

  return projects.map((project, index) => {
    const syncedItems = syncDocumentItems(removeGeneratedWikiGroups(project.items), documents);
    if (index !== 0) return { ...project, items: syncedItems };
    return { ...project, items: [...syncedItems, ...backendItems, ...wikiGroups] };
  });
}

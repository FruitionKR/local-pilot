import { makeRawId } from "@/entities/graph/lib/graph";
import type { DropTarget, Project, TreeItem } from "@/entities/tree/model/tree";
import type { DocumentUploadResponse } from "@/entities/document/model/document";
import { createClientId, isFileItem, isWikiItem } from "./guards";
import { findTreeItem } from "./queries";

function itemContainsId(item: TreeItem, itemId: string): boolean {
  if (item.id === itemId) return true;
  return item.children?.some((child) => itemContainsId(child, itemId)) ?? false;
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
  if (target.targetId === null) return [...items, movedItem];
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
  if (target.targetId === null) return [...result.items, result.removed];
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

/** 문서나 폴더를 같은 프로젝트 또는 다른 프로젝트의 지정 위치로 이동한다. */
export function moveProjectTreeItem(
  projects: Project[],
  sourceProjectId: string,
  itemId: string,
  target: DropTarget
): Project[] {
  const sourceProject = projects.find((project) => project.id === sourceProjectId);
  const targetProject = projects.find((project) => project.id === target.projectId);
  if (!sourceProject || !targetProject) return projects;

  if (sourceProjectId === target.projectId) {
    const dragged = findTreeItem(sourceProject.items, itemId);
    const targetItem = target.targetId ? findTreeItem(sourceProject.items, target.targetId) : null;
    let nextItems: TreeItem[];
    if (target.position === "inside" && target.targetId && dragged && targetItem && isFileItem(dragged) && isFileItem(targetItem)) {
      nextItems = mergeTreeItemsIntoFolder(sourceProject.items, itemId, target.targetId);
    } else {
      const normalizedTarget = target.position === "inside" && targetItem && isFileItem(targetItem)
        ? { ...target, position: "after" as const }
        : target;
      nextItems = moveTreeItem(sourceProject.items, itemId, normalizedTarget);
    }
    if (nextItems === sourceProject.items) return projects;
    return projects.map((project) => project.id === sourceProjectId ? { ...project, items: nextItems } : project);
  }

  const sourceResult = removeTreeItem(sourceProject.items, itemId);
  const movedItem = sourceResult.removed;
  if (!movedItem || isWikiItem(movedItem)) return projects;

  let nextTargetItems: TreeItem[];
  if (target.targetId === null) {
    nextTargetItems = [...targetProject.items, movedItem];
  } else {
    const targetItem = findTreeItem(targetProject.items, target.targetId);
    if (!targetItem) return projects;
    if (target.position === "inside" && isFileItem(movedItem) && isFileItem(targetItem)) {
      const folder: TreeItem = {
        id: createClientId("merged-folder"),
        label: "새 문서 묶음",
        type: "folder",
        children: [targetItem, movedItem]
      };
      nextTargetItems = replaceTreeItem(targetProject.items, target.targetId, folder);
    } else {
      const normalizedTarget = target.position === "inside" && isFileItem(targetItem)
        ? { ...target, position: "after" as const }
        : target;
      nextTargetItems = insertTreeItem(targetProject.items, movedItem, normalizedTarget);
    }
  }

  return projects.map((project) => {
    if (project.id === sourceProjectId) return { ...project, items: sourceResult.items };
    if (project.id === target.projectId) return { ...project, items: nextTargetItems };
    return project;
  });
}

export function updateTreeItemLabel(items: TreeItem[], itemId: string, label: string): TreeItem[] {
  return mapTreeItemById(items, itemId, (item) => ({ ...item, label, customLabel: true }));
}

export function updateDocumentItemLabel(items: TreeItem[], documentId: string, label: string): TreeItem[] {
  return items.map((item) => {
    const nextItem = item.documentId === documentId
      ? { ...item, label, customLabel: false }
      : item;
    if (nextItem.children?.length) {
      return { ...nextItem, children: updateDocumentItemLabel(nextItem.children, documentId, label) };
    }
    return nextItem;
  });
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

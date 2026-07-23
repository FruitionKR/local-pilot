import type { TreeItem } from "@/entities/tree/model/tree";

export function findTreeItem(items: TreeItem[], itemId: string): TreeItem | null {
  for (const item of items) {
    if (item.id === itemId) return item;
    const found = item.children ? findTreeItem(item.children, itemId) : null;
    if (found) return found;
  }
  return null;
}

export function findTreeItemByDocumentId(items: TreeItem[], documentId: string): TreeItem | null {
  for (const item of items) {
    if (item.documentId === documentId) return item;
    const found = item.children ? findTreeItemByDocumentId(item.children, documentId) : null;
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

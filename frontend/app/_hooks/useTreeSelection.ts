import { useMemo, useState } from "react";
import { makeSourceId, nodeIdToPageType } from "../_lib/graph";
import { findTreeItem, findTreeItemByGraphNodeId } from "../_lib/tree";
import type { Project } from "../_lib/types";

export type PreviewTarget = {
  pageId: string | null;
  title: string;
  pageType: string | null;
};

export function useTreeSelection(projects: Project[]) {
  const [focusedGraphNodeId, setFocusedGraphNodeId] = useState<string | null>(null);
  const [selectedTreeItemId, setSelectedTreeItemId] = useState<string | null>(null);
  const [selectedPreviewTarget, setSelectedPreviewTarget] = useState<PreviewTarget | null>(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState<string | null>(null);
  const selectedDocumentTitle = useMemo(() => {
    if (selectedPreviewTarget) return selectedPreviewTarget.title;
    if (!selectedTreeItemId) return null;
    for (const project of projects) {
      const item = findTreeItem(project.items, selectedTreeItemId);
      if (item) return item.label;
    }
    return null;
  }, [projects, selectedPreviewTarget, selectedTreeItemId]);

  function selectTreeGraphNode(item: { id: string; label: string; documentId?: string; graphNodeId?: string }) {
    const isRawNode = item.graphNodeId?.startsWith("raw:");
    const pageId = isRawNode
      ? null
      : (item.documentId ? makeSourceId(item.documentId) : null) || (nodeIdToPageType(item.graphNodeId ?? "") !== null ? item.graphNodeId ?? null : null);
    setSelectedTreeItemId(item.id);
    setSelectedPreviewTarget(pageId ? {
      pageId,
      title: item.label,
      pageType: nodeIdToPageType(pageId) ?? "source"
    } : null);
    setFocusedGraphNodeId(pageId || item.graphNodeId || null);
    setSelectedDocumentId(isRawNode ? (item.documentId ?? null) : null);
  }

  function openGraphNodePreview(nodeId: string, title: string) {
    const pageType = nodeIdToPageType(nodeId);
    setSelectedTreeItemId(null);
    setSelectedPreviewTarget({
      pageId: pageType !== null ? nodeId : null,
      title,
      pageType
    });
    setFocusedGraphNodeId(nodeId);
    setSelectedDocumentId(null);
  }

  function openWikiPagePreview(pageId: string, title: string, pageType: string) {
    let treeItemId: string | null = null;
    for (const project of projects) {
      const found = findTreeItemByGraphNodeId(project.items, pageId);
      if (found) { treeItemId = found.id; break; }
    }
    setSelectedTreeItemId(treeItemId);
    setSelectedPreviewTarget({ pageId, title, pageType });
    setFocusedGraphNodeId(pageId);
    setSelectedDocumentId(null);
  }

  function clearTreeGraphSelection() {
    setSelectedTreeItemId(null);
    setSelectedPreviewTarget(null);
    setFocusedGraphNodeId(null);
    setSelectedDocumentId(null);
  }

  return {
    focusedGraphNodeId,
    selectedTreeItemId,
    selectedDocumentTitle,
    selectedPreviewTarget,
    selectedDocumentId,
    selectTreeGraphNode,
    openGraphNodePreview,
    openWikiPagePreview,
    clearTreeGraphSelection
  };
}

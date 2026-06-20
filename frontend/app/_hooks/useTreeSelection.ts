import { useMemo, useState } from "react";
import { makeSourceId, nodeIdToPageType } from "../_lib/graph";
import { findTreeItem } from "../_lib/tree";
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
    const sourcePageId = item.documentId ? makeSourceId(item.documentId) : null;
    const pageId = sourcePageId || (nodeIdToPageType(item.graphNodeId ?? "") !== null ? item.graphNodeId ?? null : null);
    setSelectedTreeItemId(item.id);
    setSelectedPreviewTarget(pageId ? {
      pageId,
      title: item.label,
      pageType: nodeIdToPageType(pageId) ?? "source"
    } : null);
    setFocusedGraphNodeId(pageId || item.graphNodeId || null);
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
  }

  function openWikiPagePreview(pageId: string, title: string, pageType: string) {
    setSelectedTreeItemId(null);
    setSelectedPreviewTarget({ pageId, title, pageType });
    setFocusedGraphNodeId(pageId);
  }

  function clearTreeGraphSelection() {
    setSelectedTreeItemId(null);
    setSelectedPreviewTarget(null);
    setFocusedGraphNodeId(null);
  }

  return {
    focusedGraphNodeId,
    selectedTreeItemId,
    selectedDocumentTitle,
    selectedPreviewTarget,
    selectTreeGraphNode,
    openGraphNodePreview,
    openWikiPagePreview,
    clearTreeGraphSelection
  };
}

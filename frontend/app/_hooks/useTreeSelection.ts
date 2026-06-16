import { useMemo, useState } from "react";
import { findTreeItem } from "../_lib/tree";
import type { Project } from "../_lib/types";

export function useTreeSelection(projects: Project[]) {
  const [focusedGraphNodeId, setFocusedGraphNodeId] = useState<string | null>(null);
  const [selectedTreeItemId, setSelectedTreeItemId] = useState<string | null>(null);
  const [selectedGraphPreviewTitle, setSelectedGraphPreviewTitle] = useState<string | null>(null);
  const selectedDocumentTitle = useMemo(() => {
    if (selectedGraphPreviewTitle) return selectedGraphPreviewTitle;
    if (!selectedTreeItemId) return null;
    for (const project of projects) {
      const item = findTreeItem(project.items, selectedTreeItemId);
      if (item) return item.label;
    }
    return null;
  }, [projects, selectedGraphPreviewTitle, selectedTreeItemId]);

  function selectTreeGraphNode(itemId: string, nodeId: string) {
    setSelectedTreeItemId(itemId);
    setSelectedGraphPreviewTitle(null);
    setFocusedGraphNodeId(nodeId);
  }

  function openGraphNodePreview(nodeId: string, title: string) {
    setSelectedTreeItemId(null);
    setSelectedGraphPreviewTitle(title);
    setFocusedGraphNodeId(nodeId);
  }

  function clearTreeGraphSelection() {
    setSelectedTreeItemId(null);
    setSelectedGraphPreviewTitle(null);
    setFocusedGraphNodeId(null);
  }

  return {
    focusedGraphNodeId,
    selectedTreeItemId,
    selectedDocumentTitle,
    selectTreeGraphNode,
    openGraphNodePreview,
    clearTreeGraphSelection
  };
}

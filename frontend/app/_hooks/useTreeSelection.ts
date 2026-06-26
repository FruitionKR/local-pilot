import { useMemo, useState } from "react";
import { makeSourceId, nodeIdToPageType, rawNodeIdToDocumentId } from "../_lib/graph";
import { findTreeItem, findTreeItemByGraphNodeId } from "../_lib/tree";
import type { Project, SourceBlockHighlight } from "../_lib/types";

export type PreviewTarget = {
  pageId: string | null;
  title: string;
  pageType: string | null;
  sourceBlockHighlights?: SourceBlockHighlight[];
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

  function findTreeItemIdByGraphNodeId(nodeId: string) {
    for (const project of projects) {
      const found = findTreeItemByGraphNodeId(project.items, nodeId);
      if (found) return found.id;
    }
    return null;
  }

  function openPreviewTarget({
    nodeId,
    title,
    treeItemId,
    documentId = null
  }: {
    nodeId: string;
    title: string;
    treeItemId?: string | null;
    documentId?: string | null;
  }) {
    const rawDocumentId = rawNodeIdToDocumentId(nodeId) ?? documentId;
    const pageType = nodeIdToPageType(nodeId);
    const resolvedTreeItemId = treeItemId ?? findTreeItemIdByGraphNodeId(nodeId);

    setSelectedTreeItemId(resolvedTreeItemId);
    setSelectedPreviewTarget({ pageId: pageType ? nodeId : null, title, pageType });
    setFocusedGraphNodeId(nodeId);
    setSelectedDocumentId(pageType ? null : rawDocumentId);
  }

  function selectTreeGraphNode(item: { id: string; label: string; documentId?: string; graphNodeId?: string }) {
    const nodeId = item.graphNodeId ?? (item.documentId ? makeSourceId(item.documentId) : null);
    if (!nodeId) return;
    openPreviewTarget({
      nodeId,
      title: item.label,
      treeItemId: item.id,
      documentId: item.documentId ?? null
    });
  }

  function openGraphNodePreview(nodeId: string, title: string) {
    openPreviewTarget({ nodeId, title });
  }

  function openWikiPagePreview(pageId: string, title: string, pageType: string) {
    openPreviewTarget({ nodeId: pageId, title, treeItemId: findTreeItemIdByGraphNodeId(pageId) });
  }

  function openSourceBlockPreview(documentId: string, title: string, sourceBlockHighlights: SourceBlockHighlight[]) {
    const nodeId = makeSourceId(documentId);
    setSelectedTreeItemId(findTreeItemIdByGraphNodeId(nodeId));
    setSelectedPreviewTarget({ pageId: null, title, pageType: null, sourceBlockHighlights });
    setFocusedGraphNodeId(nodeId);
    setSelectedDocumentId(documentId);
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
    openSourceBlockPreview,
    clearTreeGraphSelection
  };
}

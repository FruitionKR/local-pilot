import { useMemo, useState } from "react";
import { NODE_PREFIX, makeSourceId, nodeIdToPageType, rawNodeIdToDocumentId } from "../_lib/graph";
import { findTreeItem, findTreeItemByGraphNodeId } from "../_lib/tree";
import type { Project, SourceBlockHighlight } from "../_lib/types";

export type PreviewTarget = {
  pageId: string | null;
  title: string;
  pageType: string | null;
  sourceBlockHighlights?: SourceBlockHighlight[];
};

// 트리/그래프 선택 관련 상태는 항상 함께 변경되므로 하나의 객체로 관리한다.
type TreeSelectionState = {
  focusedGraphNodeId: string | null;
  selectedTreeItemId: string | null;
  selectedPreviewTarget: PreviewTarget | null;
  selectedDocumentId: string | null;
};

const EMPTY_SELECTION: TreeSelectionState = {
  focusedGraphNodeId: null,
  selectedTreeItemId: null,
  selectedPreviewTarget: null,
  selectedDocumentId: null
};

export function useTreeSelection(projects: Project[]) {
  const [selection, setSelection] = useState<TreeSelectionState>(EMPTY_SELECTION);
  const { focusedGraphNodeId, selectedTreeItemId, selectedPreviewTarget, selectedDocumentId } = selection;
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

    setSelection({
      selectedTreeItemId: resolvedTreeItemId,
      selectedPreviewTarget: { pageId: pageType ? nodeId : null, title, pageType },
      focusedGraphNodeId: nodeId,
      selectedDocumentId: pageType ? null : rawDocumentId
    });
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
    const sourceNodeId = makeSourceId(documentId);
    const rawNodeId = `${NODE_PREFIX.raw}${documentId}`;
    setSelection({
      selectedTreeItemId: findTreeItemIdByGraphNodeId(rawNodeId) ?? findTreeItemIdByGraphNodeId(sourceNodeId),
      selectedPreviewTarget: { pageId: null, title, pageType: null, sourceBlockHighlights },
      focusedGraphNodeId: sourceNodeId,
      selectedDocumentId: documentId
    });
  }

  function clearTreeGraphSelection() {
    setSelection(EMPTY_SELECTION);
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

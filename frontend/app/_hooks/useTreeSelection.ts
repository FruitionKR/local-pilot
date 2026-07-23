import { useMemo, useState } from "react";
import { findSourceNodeByDocumentId, makeRawId, rawNodeIdToDocumentId } from "../_lib/graph";
import { findTreeItem, findTreeItemByGraphNodeId } from "../_lib/tree";
import type { GraphNode, Project, SourceBlockHighlight } from "../_lib/types";

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

export function useTreeSelection(projects: Project[], nodes: GraphNode[]) {
  const [selection, setSelection] = useState<TreeSelectionState>(EMPTY_SELECTION);
  const { focusedGraphNodeId, selectedTreeItemId, selectedPreviewTarget, selectedDocumentId } = selection;
  const selectedDocumentTitle = useMemo(() => {
    if (selectedTreeItemId) {
      for (const project of projects) {
        const item = findTreeItem(project.items, selectedTreeItemId);
        if (item) return item.label;
      }
    }
    return selectedPreviewTarget?.title ?? null;
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
    pageType = null,
    treeItemId,
    documentId = null
  }: {
    nodeId: string;
    title: string;
    pageType?: string | null;
    treeItemId?: string | null;
    documentId?: string | null;
  }) {
    // wiki page(source/concept)면 pageId로 상세 조회, 아니면 raw 문서로 처리한다.
    const isWikiPage = pageType === "source" || pageType === "concept";
    const rawDocumentId = rawNodeIdToDocumentId(nodeId) ?? documentId;
    const resolvedTreeItemId = treeItemId ?? findTreeItemIdByGraphNodeId(nodeId);

    setSelection({
      selectedTreeItemId: resolvedTreeItemId,
      selectedPreviewTarget: { pageId: isWikiPage ? nodeId : null, title, pageType: isWikiPage ? pageType : null },
      focusedGraphNodeId: nodeId,
      selectedDocumentId: isWikiPage ? null : rawDocumentId
    });
  }

  function selectTreeGraphNode(item: { id: string; label: string; documentId?: string; graphNodeId?: string }) {
    const nodeId = item.graphNodeId ?? (item.documentId ? makeRawId(item.documentId) : null);
    if (!nodeId) return;
    openPreviewTarget({
      nodeId,
      title: item.label,
      treeItemId: item.id,
      documentId: item.documentId ?? null
    });
  }

  function openGraphNodePreview(node: GraphNode) {
    const kind = node.kind ?? "concept";
    openPreviewTarget({
      nodeId: node.id,
      title: node.label,
      pageType: kind === "raw" ? null : kind,
      documentId: node.documentId ?? null
    });
  }

  function openWikiPagePreview(pageId: string, title: string, pageType: string) {
    openPreviewTarget({ nodeId: pageId, title, pageType, treeItemId: findTreeItemIdByGraphNodeId(pageId) });
  }

  function openSourceBlockPreview(documentId: string, title: string, sourceBlockHighlights: SourceBlockHighlight[]) {
    const rawNodeId = makeRawId(documentId);
    const sourceNodeId = findSourceNodeByDocumentId(nodes, documentId)?.id ?? null;
    setSelection({
      selectedTreeItemId: findTreeItemIdByGraphNodeId(rawNodeId) ?? (sourceNodeId ? findTreeItemIdByGraphNodeId(sourceNodeId) : null),
      selectedPreviewTarget: { pageId: null, title, pageType: null, sourceBlockHighlights },
      focusedGraphNodeId: sourceNodeId ?? rawNodeId,
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

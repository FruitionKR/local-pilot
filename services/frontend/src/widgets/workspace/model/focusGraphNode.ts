type GraphSelectionState = {
  focusedGraphNodeId: string | null;
  selectedTreeItemId: string | null;
  selectedPreviewTarget: unknown;
  selectedDocumentId: string | null;
};

/** 그래프 클릭은 문서 선택을 바꾸지 않고 캔버스 포커스만 이동한다. */
export function focusGraphNode<T extends GraphSelectionState>(selection: T, nodeId: string): T {
  return { ...selection, focusedGraphNodeId: nodeId };
}

import type { Dispatch, MouseEvent as ReactMouseEvent, MutableRefObject, PointerEvent as ReactPointerEvent, SetStateAction } from "react";
import type { GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { canvasToGraphPosition, graphDistanceInfluence } from "./graphGeometry";
import { getGraphDistances } from "./graphPhysics";

export function useGraphPointer({
  nodes,
  links,
  canvasRef,
  nodePositionsRef,
  visibleNodeCountRef,
  initialNodePositions,
  draggingNodeIdRef,
  panDragRef,
  isPointerHeldRef,
  activePointerIdRef,
  externalFocusedNodeIdRef,
  graphPanRef,
  graphZoomRef,
  drawGraphRef,
  nodeSize,
  graphToCanvas,
  clampPosition,
  clampPan,
  resolveCollisions,
  scheduleGraphCacheWrite,
  setGraphPan,
  setDraggingNodeId,
  onOpenNodePreview,
  setFocusedNode,
  stopDragging,
  stopPanning
}: {
  nodes: GraphNode[];
  links: Parameters<typeof getGraphDistances>[0];
  canvasRef: MutableRefObject<HTMLCanvasElement | null>;
  nodePositionsRef: MutableRefObject<NodePositionMap>;
  visibleNodeCountRef: MutableRefObject<number>;
  initialNodePositions: NodePositionMap;
  draggingNodeIdRef: MutableRefObject<string | null>;
  panDragRef: MutableRefObject<{ pointerId: number; button: number; startX: number; startY: number; startPan: NodePosition } | null>;
  isPointerHeldRef: MutableRefObject<boolean>;
  activePointerIdRef: MutableRefObject<number | null>;
  externalFocusedNodeIdRef: MutableRefObject<string | null>;
  graphPanRef: MutableRefObject<NodePosition>;
  graphZoomRef: MutableRefObject<number>;
  drawGraphRef: MutableRefObject<() => void>;
  nodeSize: (node: GraphNode) => number;
  graphToCanvas: (position: NodePosition, canvas: HTMLCanvasElement) => NodePosition;
  clampPosition: (position: NodePosition, nodeId?: string) => NodePosition;
  clampPan: (pan: NodePosition, zoom?: number) => NodePosition;
  resolveCollisions: (positions: NodePositionMap, anchorId: string | null) => NodePositionMap;
  scheduleGraphCacheWrite: () => void;
  setGraphPan: Dispatch<SetStateAction<NodePosition>>;
  setDraggingNodeId: Dispatch<SetStateAction<string | null>>;
  onOpenNodePreview: (node: GraphNode) => void;
  setFocusedNode: (nodeId: string | null) => void;
  stopDragging: (pointerId?: number) => void;
  stopPanning: (pointerId?: number) => void;
}) {
  function canvasToGraph(clientX: number, clientY: number, canvas: HTMLCanvasElement) {
    return canvasToGraphPosition({ clientX, clientY, canvas, pan: graphPanRef.current, zoom: graphZoomRef.current });
  }

  function hitTestNode(clientX: number, clientY: number) {
    const canvas = canvasRef.current;
    if (!canvas) return null;

    for (let index = visibleNodeCountRef.current - 1; index >= 0; index -= 1) {
      const node = nodes[index];
      const position = nodePositionsRef.current[node.id] ?? initialNodePositions[node.id];
      const screenPosition = graphToCanvas(position, canvas);
      const radius = nodeSize(node) / 2;
      const distance = Math.hypot(clientX - canvas.getBoundingClientRect().left - screenPosition.x, clientY - canvas.getBoundingClientRect().top - screenPosition.y);
      if (distance <= Math.max(12, radius + 6)) return node;
    }

    return null;
  }

  function moveNode(event: ReactPointerEvent<HTMLDivElement>, node: GraphNode) {
    if (!isPointerHeldRef.current || activePointerIdRef.current !== event.pointerId) return;
    if (event.pointerType === "mouse" && (event.buttons & 1) !== 1) return;
    if (
      event.clientX < 0 ||
      event.clientY < 0 ||
      event.clientX > window.innerWidth ||
      event.clientY > window.innerHeight
    ) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
      stopDragging(event.pointerId);
      return;
    }

    const canvas = canvasRef.current;
    if (!canvas) return;

    const nextPosition = clampPosition(canvasToGraph(event.clientX, event.clientY, canvas), node.id);
    const distances = getGraphDistances(links, node.id);
    const current = nodePositionsRef.current;
    const draggedPosition = current[node.id] ?? initialNodePositions[node.id];
    const moved = {
      ...current,
      ...Object.fromEntries(
        nodes.map((candidate) => {
          const currentPosition = current[candidate.id] ?? initialNodePositions[candidate.id];
          const influence = graphDistanceInfluence(distances[candidate.id]);
          const delta = {
            x: (nextPosition.x - draggedPosition.x) * influence,
            y: (nextPosition.y - draggedPosition.y) * influence
          };

          return [candidate.id, clampPosition({
            x: currentPosition.x + delta.x,
            y: currentPosition.y + delta.y
          }, candidate.id)];
        })
      )
    };

    nodePositionsRef.current = resolveCollisions(moved, node.id);
    scheduleGraphCacheWrite();
    drawGraphRef.current();
  }

  function startPanning(event: ReactPointerEvent<HTMLDivElement>) {
    const isPrimaryButton = event.button === 0;
    const isMiddleButton = event.button === 1;
    if (!isPrimaryButton && !isMiddleButton) return;

    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    const hitNode = isPrimaryButton ? hitTestNode(event.clientX, event.clientY) : null;
    if (hitNode) {
      isPointerHeldRef.current = true;
      activePointerIdRef.current = event.pointerId;
      setFocusedNode(hitNode.id);
      setDraggingNodeId(hitNode.id);
      draggingNodeIdRef.current = hitNode.id;
      drawGraphRef.current();
      return;
    }

    panDragRef.current = {
      pointerId: event.pointerId,
      button: event.button,
      startX: event.clientX,
      startY: event.clientY,
      startPan: graphPanRef.current
    };
  }

  function updatePanning(event: ReactPointerEvent<HTMLDivElement>) {
    const draggedNodeId = draggingNodeIdRef.current;
    if (draggedNodeId) {
      const node = nodes.find((candidate) => candidate.id === draggedNodeId);
      if (node) moveNode(event, node);
      return;
    }

    const panDrag = panDragRef.current;
    if (!panDrag || panDrag.pointerId !== event.pointerId) return;
    const expectedButtonMask = panDrag.button === 1 ? 4 : 1;
    if (event.pointerType === "mouse" && (event.buttons & expectedButtonMask) !== expectedButtonMask) {
      stopPanning(event.pointerId);
      return;
    }

    const nextPan = clampPan({
      x: panDrag.startPan.x + event.clientX - panDrag.startX,
      y: panDrag.startPan.y + event.clientY - panDrag.startY
    }, graphZoomRef.current);
    graphPanRef.current = nextPan;
    setGraphPan(nextPan);
    scheduleGraphCacheWrite();
  }

  function updateHover(event: ReactPointerEvent<HTMLDivElement>) {
    if (draggingNodeIdRef.current || panDragRef.current) return;
    if (externalFocusedNodeIdRef.current) return;
    setFocusedNode(hitTestNode(event.clientX, event.clientY)?.id ?? null);
  }

  function stopCanvasPanning(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    const wasDragging = activePointerIdRef.current === event.pointerId;
    if (wasDragging) {
      stopDragging(event.pointerId);
      if (!externalFocusedNodeIdRef.current) {
        setFocusedNode(hitTestNode(event.clientX, event.clientY)?.id ?? null);
      }
    }
    if (panDragRef.current?.pointerId !== event.pointerId) return;
    stopPanning(event.pointerId);
  }

  function openNodePreview(event: ReactMouseEvent<HTMLDivElement>) {
    const node = hitTestNode(event.clientX, event.clientY);
    if (!node) return;
    event.preventDefault();
    event.stopPropagation();
    setFocusedNode(node.id);
    onOpenNodePreview(node);
  }

  return {
    onPointerDown: startPanning,
    onPointerMove: (event: ReactPointerEvent<HTMLDivElement>) => {
      updatePanning(event);
      updateHover(event);
    },
    onPointerUp: stopCanvasPanning,
    onPointerCancel: stopCanvasPanning,
    onDoubleClick: openNodePreview,
    onAuxClick: (event: ReactPointerEvent<HTMLDivElement>) => event.preventDefault(),
    onPointerLeave: () => {
      if (!draggingNodeIdRef.current && !panDragRef.current && !externalFocusedNodeIdRef.current) {
        setFocusedNode(null);
      }
    },
    onLostPointerCapture: (event: ReactPointerEvent<HTMLDivElement>) => {
      stopDragging(event.pointerId);
      stopPanning(event.pointerId);
    }
  };
}

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { GraphCache, GraphLink, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { GRAPH_ZOOM, linkKey } from "../../_lib/graph";
import { readStoredGraphCache, writeStoredGraphCache } from "./graphCache";
import { drawGraphFrame } from "./graphDrawing";
import { clampGraphPan, clampGraphPosition, clampGraphZoom, graphToCanvasPosition } from "./graphGeometry";
import {
  buildLinkForces,
  buildNodeSizes,
  buildPairForces,
  createSourceCenteredNodePositions,
  getGraphDistances,
  resolveGraphCollisions,
  tickGraphPositions
} from "./graphPhysics";
import { useGraphAnimation } from "./useGraphAnimation";
import { useGraphPointer } from "./useGraphPointer";

const HOVER_SMOOTHING_MS = 320;
const HOVER_SETTLE_THRESHOLD = 0.002;
const GRAPH_CACHE_DEBOUNCE_MS = 700;
/** graph cache signature의 레이아웃 버전 prefix */
const GRAPH_LAYOUT_VERSION = "api-layout-v3";

export function useGraphCanvas({ nodes = [], links = [], focusedNodeId, onOpenNodePreview }: {
  nodes: GraphNode[];
  links: GraphLink[];
  focusedNodeId: string | null;
  onOpenNodePreview: (node: GraphNode) => void;
}) {
  const graphSignature = useMemo(
    () => {
      const nodeSignature = nodes.map((node) => node.id).sort().join("|");
      const linkSignature = links.map((link) => linkKey(link.from, link.to)).sort().join("|");
      return `${GRAPH_LAYOUT_VERSION}:${nodeSignature}:${linkSignature}`;
    },
    [links, nodes]
  );
  const nodeById = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes]);
  const nodeSizes = useMemo(() => buildNodeSizes(nodes), [nodes]);
  const linkedNodePairs = useMemo(() => new Set(links.map((link) => linkKey(link.from, link.to))), [links]);

  function isRawSourceLink(link: GraphLink) {
    const from = nodeById.get(link.from);
    const to = nodeById.get(link.to);
    const kinds = [from?.kind, to?.kind];
    return kinds.includes("raw") && kinds.includes("source");
  }

  function nodeSize(node: GraphNode) {
    return nodeSizes[node.id] ?? 14;
  }

  const linkForces = useMemo(() => buildLinkForces({ links, nodes }), [links, nodes]);
  const pairForces = useMemo(
    () => buildPairForces({ nodes, linkedNodePairs, nodeSize, linkKey }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [linkedNodePairs, nodes, nodeSizes]
  );

  const initialNodePositions = useMemo(
    () => createSourceCenteredNodePositions(nodes, links),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [graphSignature]
  );

  function readGraphCacheForCurrentGraph() {
    return readStoredGraphCache({ signature: graphSignature, nodes });
  }

  // 초기 렌더에서 graph cache를 한 번만 읽어 positions/pan/zoom 초기값에 함께 사용한다.
  const initialCacheRef = useRef<GraphCache | null | undefined>(undefined);
  if (initialCacheRef.current === undefined) {
    initialCacheRef.current = readGraphCacheForCurrentGraph();
  }
  const initialCache = initialCacheRef.current;

  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null);
  const [visibleNodeCount, setVisibleNodeCount] = useState(0);
  const [graphZoom, setGraphZoom] = useState(() => initialCache?.zoom ?? 1);
  const [graphPan, setGraphPan] = useState<NodePosition>(() => initialCache?.pan ?? { x: 0, y: 0 });
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const nodePositionsRef = useRef<NodePositionMap>(initialCache?.positions ?? initialNodePositions);
  const selectedNodeIdRef = useRef<string | null>(null);
  const hoveredNodeIdRef = useRef<string | null>(null);
  const nodeHoverAmountsRef = useRef<Record<string, number>>({});
  const externalFocusedNodeIdRef = useRef<string | null>(focusedNodeId);
  const draggingNodeIdRef = useRef(draggingNodeId);
  const visibleNodeCountRef = useRef(visibleNodeCount);
  const graphZoomRef = useRef(graphZoom);
  const graphPanRef = useRef(graphPan);
  const panDragRef = useRef<{ pointerId: number; button: number; startX: number; startY: number; startPan: NodePosition } | null>(null);
  const isPointerHeldRef = useRef(false);
  const activePointerIdRef = useRef<number | null>(null);
  const cacheWriteRef = useRef<number | null>(null);
  const tickGraphRef = useRef<(positions: NodePositionMap, anchorId: string | null) => NodePositionMap>((positions) => positions);
  const advanceHoverAnimationRef = useRef<(deltaMs: number) => boolean>(() => false);
  const drawGraphRef = useRef<() => void>(() => {});
  const isRevealingGraph = visibleNodeCount < nodes.length;

  useEffect(() => {
    const cached = readGraphCacheForCurrentGraph();
    const nextPositions = Object.fromEntries(
      nodes.map((node) => [
        node.id,
        cached?.positions[node.id] ?? nodePositionsRef.current[node.id] ?? initialNodePositions[node.id]
      ])
    );
    const nextZoom = clampGraphZoom(cached?.zoom ?? graphZoomRef.current);
    const nextPan = clampPan(cached?.pan ?? graphPanRef.current, nextZoom);
    nodePositionsRef.current = nextPositions;
    graphPanRef.current = nextPan;
    graphZoomRef.current = nextZoom;
    setGraphPan(nextPan);
    setGraphZoom(nextZoom);
    setVisibleNodeCount(nodes.length);
    const externalId = externalFocusedNodeIdRef.current;
    selectedNodeIdRef.current = externalId;
    hoveredNodeIdRef.current = externalId;
    nodeHoverAmountsRef.current = externalId ? { [externalId]: nodeHoverAmountsRef.current[externalId] ?? 1 } : {};
    drawGraphRef.current();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphSignature, initialNodePositions]);

  useEffect(() => {
    externalFocusedNodeIdRef.current = focusedNodeId;
  }, [focusedNodeId]);

  useEffect(() => {
    draggingNodeIdRef.current = draggingNodeId;
  }, [draggingNodeId]);

  useEffect(() => {
    visibleNodeCountRef.current = visibleNodeCount;
    drawGraphRef.current();
  }, [visibleNodeCount]);

  useEffect(() => {
    graphZoomRef.current = graphZoom;
    drawGraphRef.current();
  }, [graphZoom]);

  useEffect(() => {
    graphPanRef.current = graphPan;
    drawGraphRef.current();
  }, [graphPan]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const nextPan = clampPan(graphPanRef.current, graphZoomRef.current);
    graphPanRef.current = nextPan;
    setGraphPan(nextPan);
    drawGraphRef.current();
    const observer = new ResizeObserver(() => {
      const resizedPan = clampPan(graphPanRef.current, graphZoomRef.current);
      graphPanRef.current = resizedPan;
      setGraphPan(resizedPan);
      drawGraphRef.current();
    });
    observer.observe(canvas);
    return () => observer.disconnect();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      event.stopPropagation();
      const nextZoom = clampGraphZoom(graphZoomRef.current * Math.exp(-event.deltaY * GRAPH_ZOOM.wheelSensitivity));
      const nextPan = clampPan(graphPanRef.current, nextZoom);
      graphZoomRef.current = nextZoom;
      graphPanRef.current = nextPan;
      setGraphZoom(nextZoom);
      setGraphPan(nextPan);
      scheduleGraphCacheWrite();
    };

    canvas.addEventListener("wheel", handleWheel, { passive: false });
    return () => canvas.removeEventListener("wheel", handleWheel);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    setVisibleNodeCount(nodes.length);
  }, [graphSignature, nodes.length]);

  function scheduleGraphCacheWrite() {
    if (typeof window === "undefined") return;
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);

    cacheWriteRef.current = window.setTimeout(() => {
      writeStoredGraphCache({
        signature: graphSignature,
        positions: nodePositionsRef.current,
        pan: graphPanRef.current,
        zoom: graphZoomRef.current
      });
      cacheWriteRef.current = null;
    }, GRAPH_CACHE_DEBOUNCE_MS);
  }

  useEffect(() => () => {
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);
  }, []);

  const setHoveredNode = useCallback((nodeId: string | null) => {
    if (hoveredNodeIdRef.current === nodeId) return;
    hoveredNodeIdRef.current = nodeId;
    drawGraphRef.current();
  }, []);

  const setSelectedNode = useCallback((nodeId: string | null) => {
    if (selectedNodeIdRef.current === nodeId) return;
    selectedNodeIdRef.current = nodeId;
    hoveredNodeIdRef.current = nodeId;
    drawGraphRef.current();
  }, []);

  useEffect(() => {
    if (focusedNodeId && nodeById.has(focusedNodeId)) {
      setSelectedNode(focusedNodeId);
    } else {
      setSelectedNode(null);
    }
  }, [focusedNodeId, nodeById, setSelectedNode]);

  const stopDragging = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && activePointerIdRef.current !== pointerId) return;
    isPointerHeldRef.current = false;
    activePointerIdRef.current = null;
    setDraggingNodeId(null);
    draggingNodeIdRef.current = null;
  }, []);

  const stopPanning = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && panDragRef.current?.pointerId !== pointerId) return;
    panDragRef.current = null;
  }, []);

  useEffect(() => {
    const stopActiveDrag = (event?: PointerEvent) => {
      stopDragging(event?.pointerId);
      stopPanning(event?.pointerId);
    };
    const stopDragOnBlur = () => {
      stopDragging();
      stopPanning();
    };

    window.addEventListener("pointerup", stopActiveDrag);
    window.addEventListener("pointercancel", stopActiveDrag);
    window.addEventListener("blur", stopDragOnBlur);
    return () => {
      window.removeEventListener("pointerup", stopActiveDrag);
      window.removeEventListener("pointercancel", stopActiveDrag);
      window.removeEventListener("blur", stopDragOnBlur);
    };
  }, [stopDragging, stopPanning]);

  function graphToCanvas(position: NodePosition, canvas: HTMLCanvasElement) {
    return graphToCanvasPosition({ position, canvas, pan: graphPanRef.current, zoom: graphZoomRef.current });
  }

  function drawGraph() {
    const canvas = canvasRef.current;
    if (!canvas) return;

    drawGraphFrame({
      canvas,
      nodes,
      links,
      visibleNodeCount: visibleNodeCountRef.current,
      positions: nodePositionsRef.current,
      initialNodePositions,
      nodeHoverAmounts: nodeHoverAmountsRef.current,
      graphToCanvas,
      nodeSize,
      isRawSourceLink
    });
  }

  // render 중 ref 할당을 피하기 위해 매 렌더 후 layout effect에서 갱신한다.
  useLayoutEffect(() => {
    drawGraphRef.current = drawGraph;
  });

  function advanceHoverAnimation(deltaMs: number) {
    const targetNodeId = hoveredNodeIdRef.current ?? selectedNodeIdRef.current;
    const amounts = nodeHoverAmountsRef.current;
    const smoothing = 1 - Math.exp(-deltaMs / HOVER_SMOOTHING_MS);
    let changed = false;

    Object.keys(amounts).forEach((nodeId) => {
      if (nodeById.has(nodeId)) return;
      delete amounts[nodeId];
      changed = true;
    });

    nodes.forEach((node) => {
      const target = node.id === targetNodeId ? 1 : 0;
      const current = amounts[node.id] ?? 0;
      const next = current + (target - current) * smoothing;
      const settled = Math.abs(next - target) <= HOVER_SETTLE_THRESHOLD ? target : next;

      if (Math.abs(settled - current) > HOVER_SETTLE_THRESHOLD) changed = true;
      if (settled <= HOVER_SETTLE_THRESHOLD && target === 0) {
        if (amounts[node.id] !== undefined) {
          delete amounts[node.id];
          changed = true;
        }
        return;
      }

      amounts[node.id] = settled;
    });

    return changed;
  }

  advanceHoverAnimationRef.current = advanceHoverAnimation;

  function clampPan(nextPan: NodePosition, nextZoom = graphZoomRef.current) {
    const canvas = canvasRef.current;
    if (!canvas) return nextPan;
    return clampGraphPan({ canvas, pan: nextPan, zoom: nextZoom });
  }

  function clampPosition(position: NodePosition, nodeId?: string) {
    const node = nodes.find((candidate) => candidate.id === nodeId);
    return clampGraphPosition({ position, node, nodeSize: node ? nodeSize(node) : 0 });
  }

  function resolveCollisions(positions: NodePositionMap, anchorId: string | null) {
    return resolveGraphCollisions({ nodes, positions, initialNodePositions, pairForces, anchorId, clampPosition });
  }

  function tickGraph(positions: NodePositionMap, anchorId: string | null) {
    return tickGraphPositions({
      nodes,
      positions,
      initialNodePositions,
      linkForces,
      pairForces,
      anchorId,
      isRevealingGraph,
      clampPosition
    });
  }

  tickGraphRef.current = tickGraph;

  useGraphAnimation({
    tickGraphRef,
    advanceHoverAnimationRef,
    nodePositionsRef,
    draggingNodeIdRef,
    drawGraphRef,
    scheduleGraphCacheWrite
  });

  const graphCanvasProps = useGraphPointer({
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
    setHoveredNode,
    setSelectedNode,
    stopDragging,
    stopPanning
  });

  return {
    canvasRef,
    graphCanvasProps
  };
}

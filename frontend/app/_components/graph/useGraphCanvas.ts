import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { GraphLink, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { GRAPH_ZOOM, linkKey } from "../../_lib/graph";
import { readStoredGraphCache, writeStoredGraphCache } from "./graphCache";
import { drawGraphFrame } from "./graphDrawing";
import { clampGraphPan, clampGraphPosition, clampGraphZoom, graphToCanvasPosition } from "./graphGeometry";
import {
  buildLinkForces,
  buildNodeDegrees,
  buildNodeSizes,
  buildPairForces,
  createRandomNodePositions,
  getGraphDistances,
  resolveGraphCollisions,
  tickGraphPositions
} from "./graphPhysics";
import { useGraphAnimation } from "./useGraphAnimation";
import { useGraphPointer } from "./useGraphPointer";

export function useGraphCanvas({ nodes = [], links = [], focusedNodeId, onOpenNodePreview }: {
  nodes: GraphNode[];
  links: GraphLink[];
  focusedNodeId: string | null;
  onOpenNodePreview: (node: GraphNode) => void;
}) {
  const graphSignature = useMemo(
    () => `api-layout-v1:${nodes.map((node) => node.id).sort().join("|")}`,
    [nodes]
  );
  const nodeById = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes]);
  const nodeDegrees = useMemo(() => buildNodeDegrees(nodes, links), [links, nodes]);
  const nodeSizes = useMemo(() => buildNodeSizes(nodes, nodeDegrees), [nodeDegrees, nodes]);
  const linkedNodePairs = useMemo(() => new Set(links.map((link) => linkKey(link.from, link.to))), [links]);

  function isRawSourceLink(link: GraphLink) {
    const from = nodeById.get(link.from);
    const to = nodeById.get(link.to);
    const kinds = [from?.kind, to?.kind];
    return kinds.includes("raw") && kinds.includes("source");
  }

  function nodeSize(node: GraphNode) {
    return nodeSizes[node.id] ?? 20;
  }

  const linkForces = useMemo(() => buildLinkForces({ links, nodes, nodeDegrees }), [links, nodeDegrees, nodes]);
  const pairForces = useMemo(
    () => buildPairForces({ nodes, linkedNodePairs, nodeSize, linkKey }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [linkedNodePairs, nodes, nodeSizes]
  );

  const initialNodePositions = useMemo(
    () => createRandomNodePositions(nodes),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [graphSignature]
  );

  function readGraphCacheForCurrentGraph() {
    return readStoredGraphCache({ signature: graphSignature, nodes });
  }

  function cachedOrInitialPositionsForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.positions ?? initialNodePositions;
  }

  function cachedOrInitialPanForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.pan ?? { x: 0, y: 0 };
  }

  function cachedOrInitialZoomForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.zoom ?? 1;
  }

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null);
  const [visibleNodeCount, setVisibleNodeCount] = useState(0);
  const [graphZoom, setGraphZoom] = useState(cachedOrInitialZoomForCurrentGraph);
  const [graphPan, setGraphPan] = useState<NodePosition>(cachedOrInitialPanForCurrentGraph);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const nodePositionsRef = useRef<NodePositionMap>(cachedOrInitialPositionsForCurrentGraph());
  const selectedNodeIdRef = useRef(selectedNodeId);
  const externalFocusedNodeIdRef = useRef<string | null>(focusedNodeId);
  const draggingNodeIdRef = useRef(draggingNodeId);
  const visibleNodeCountRef = useRef(visibleNodeCount);
  const graphZoomRef = useRef(graphZoom);
  const graphPanRef = useRef(graphPan);
  const panDragRef = useRef<{ pointerId: number; button: number; startX: number; startY: number; startPan: NodePosition } | null>(null);
  const focusTransitionRef = useRef<{ from: string | null; to: string | null; startedAt: number }>({ from: null, to: null, startedAt: 0 });
  const isPointerHeldRef = useRef(false);
  const activePointerIdRef = useRef<number | null>(null);
  const cacheWriteRef = useRef<number | null>(null);
  const tickGraphRef = useRef<(positions: NodePositionMap, anchorId: string | null) => NodePositionMap>((positions) => positions);
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
    const nextZoom = clampZoom(cached?.zoom ?? graphZoomRef.current);
    const nextPan = clampPan(cached?.pan ?? graphPanRef.current, nextZoom);
    nodePositionsRef.current = nextPositions;
    graphPanRef.current = nextPan;
    graphZoomRef.current = nextZoom;
    setGraphPan(nextPan);
    setGraphZoom(nextZoom);
    setVisibleNodeCount(nodes.length);
    setSelectedNodeId(null);
    selectedNodeIdRef.current = null;
    drawGraphRef.current();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphSignature, initialNodePositions]);

  useEffect(() => {
    selectedNodeIdRef.current = selectedNodeId;
  }, [selectedNodeId]);

  useEffect(() => {
    externalFocusedNodeIdRef.current = focusedNodeId;
  }, [focusedNodeId]);

  useEffect(() => {
    draggingNodeIdRef.current = draggingNodeId;
  }, [draggingNodeId]);

  useEffect(() => {
    visibleNodeCountRef.current = visibleNodeCount;
    drawGraphRef.current();
  }, [visibleNodeCount, selectedNodeId]);

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
      const nextZoom = clampZoom(graphZoomRef.current * Math.exp(-event.deltaY * GRAPH_ZOOM.wheelSensitivity));
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
    }, 700);
  }

  useEffect(() => () => {
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);
  }, []);

  const setFocusedNode = useCallback((nodeId: string | null) => {
    if (selectedNodeIdRef.current === nodeId) return;
    focusTransitionRef.current = {
      from: selectedNodeIdRef.current,
      to: nodeId,
      startedAt: performance.now()
    };
    selectedNodeIdRef.current = nodeId;
    setSelectedNodeId(nodeId);
    drawGraphRef.current();
  }, []);

  useEffect(() => {
    if (!focusedNodeId) {
      setFocusedNode(null);
      return;
    }
    if (!nodeById.has(focusedNodeId)) return;
    setFocusedNode(focusedNodeId);
  }, [focusedNodeId, nodeById, setFocusedNode]);

  const stopDragging = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && activePointerIdRef.current !== pointerId) return;
    isPointerHeldRef.current = false;
    activePointerIdRef.current = null;
    if (!externalFocusedNodeIdRef.current) setFocusedNode(null);
    setDraggingNodeId(null);
    draggingNodeIdRef.current = null;
  }, [setFocusedNode]);

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
      focusTransition: focusTransitionRef.current,
      graphToCanvas,
      nodeSize,
      isRawSourceLink
    });
  }

  drawGraphRef.current = drawGraph;

  function clampZoom(nextZoom: number) {
    return clampGraphZoom(nextZoom);
  }

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
    setFocusedNode,
    stopDragging,
    stopPanning
  });

  return {
    canvasRef,
    graphCanvasProps
  };
}

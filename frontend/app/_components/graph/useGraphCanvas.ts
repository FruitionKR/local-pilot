import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { select } from "d3-selection";
import { zoom, ZoomTransform } from "d3-zoom";
import type { ZoomBehavior } from "d3-zoom";
import type { GraphCache, GraphLink, GraphNode, NodePosition } from "../../_lib/types";
import { GRAPH_CENTER, GRAPH_ZOOM, linkKey } from "../../_lib/graph";
import { readStoredGraphCache, writeStoredGraphCache } from "./graphCache";
import { drawGraphFrame } from "./graphDrawing";
import { canvasToGraphPosition, clampGraphPan, clampGraphPosition, clampGraphZoom, graphToCanvasPosition } from "./graphGeometry";
import {
  FIXED_NODE_SIZE,
  createGraphSimulation,
  createSourceCenteredNodePositions,
  simulationPositions,
  tickGraphSimulation
} from "./graphPhysics";
import type { GraphSimNode, GraphSimulation } from "./graphPhysics";
import { useGraphAnimation } from "./useGraphAnimation";
import { useGraphPointer } from "./useGraphPointer";

const HOVER_SMOOTHING_MS = 320;
const HOVER_SETTLE_THRESHOLD = 0.002;
const GRAPH_CACHE_DEBOUNCE_MS = 700;
/** graph cache signature의 레이아웃 버전 prefix */
const GRAPH_LAYOUT_VERSION = "d3-layout-v1";

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

  function isRawSourceLink(link: GraphLink) {
    const from = nodeById.get(link.from);
    const to = nodeById.get(link.to);
    const kinds = [from?.kind, to?.kind];
    return kinds.includes("raw") && kinds.includes("source");
  }

  function nodeSize(node: GraphNode) {
    if (node.kind === "raw") return 12;
    if (node.kind === "source") return 16;
    return FIXED_NODE_SIZE;
  }

  const initialNodePositions = useMemo(
    () => createSourceCenteredNodePositions(nodes, links),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [graphSignature]
  );

  function readGraphCacheForCurrentGraph() {
    return readStoredGraphCache({ signature: graphSignature, nodes });
  }

  // 초기 렌더에서 graph cache를 한 번만 읽어 pan/zoom 초기값에 사용한다.
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
  const simulationRef = useRef<GraphSimulation | null>(null);
  const zoomBehaviorRef = useRef<ZoomBehavior<HTMLCanvasElement, unknown> | null>(null);
  const selectedNodeIdRef = useRef<string | null>(null);
  const hoveredNodeIdRef = useRef<string | null>(null);
  const nodeHoverAmountsRef = useRef<Record<string, number>>({});
  const externalFocusedNodeIdRef = useRef<string | null>(focusedNodeId);
  const draggingNodeIdRef = useRef(draggingNodeId);
  const visibleNodeCountRef = useRef(visibleNodeCount);
  const graphZoomRef = useRef(graphZoom);
  const graphPanRef = useRef(graphPan);
  const isPointerHeldRef = useRef(false);
  const activePointerIdRef = useRef<number | null>(null);
  const cacheWriteRef = useRef<number | null>(null);
  const tickGraphRef = useRef<() => boolean>(() => false);
  const advanceHoverAnimationRef = useRef<(deltaMs: number) => boolean>(() => false);
  const drawGraphRef = useRef<() => void>(() => {});
  const hitTestNodeRef = useRef<(clientX: number, clientY: number) => GraphNode | null>(() => null);

  function getSimNode(nodeId: string): GraphSimNode | null {
    return simulationRef.current?.nodeById.get(nodeId) ?? null;
  }

  function getNodePosition(nodeId: string): NodePosition {
    const simNode = getSimNode(nodeId);
    if (simNode) return { x: simNode.x, y: simNode.y };
    return initialNodePositions[nodeId] ?? GRAPH_CENTER;
  }

  /** d3 ZoomTransform → 화면 pan 오프셋 (fit-scale 기준 좌표계) */
  function transformToPan(transform: ZoomTransform, canvas: HTMLCanvasElement): NodePosition {
    return {
      x: transform.x + ((transform.k - 1) * canvas.clientWidth) / 2,
      y: transform.y + ((transform.k - 1) * canvas.clientHeight) / 2
    };
  }

  function panToTransform(pan: NodePosition, zoomLevel: number, canvas: HTMLCanvasElement): ZoomTransform {
    return new ZoomTransform(
      zoomLevel,
      pan.x - ((zoomLevel - 1) * canvas.clientWidth) / 2,
      pan.y - ((zoomLevel - 1) * canvas.clientHeight) / 2
    );
  }

  // graph 데이터가 바뀌면 simulation을 새로 만든다. 기존/캐시 좌표는 이어받는다.
  useEffect(() => {
    const cached = readGraphCacheForCurrentGraph();
    const previousPositions = simulationRef.current ? simulationPositions(simulationRef.current) : {};
    const startPositions = Object.fromEntries(
      nodes.map((node) => [
        node.id,
        cached?.positions[node.id] ?? previousPositions[node.id] ?? initialNodePositions[node.id]
      ])
    );
    simulationRef.current?.simulation.stop();
    simulationRef.current = createGraphSimulation({
      nodes,
      links,
      startPositions,
      originPositions: initialNodePositions
    });

    const nextZoom = clampGraphZoom(cached?.zoom ?? graphZoomRef.current);
    const nextPan = clampPan(cached?.pan ?? graphPanRef.current, nextZoom);
    applyViewState(nextPan, nextZoom);
    setVisibleNodeCount(nodes.length);
    const externalId = externalFocusedNodeIdRef.current;
    selectedNodeIdRef.current = externalId;
    hoveredNodeIdRef.current = externalId;
    nodeHoverAmountsRef.current = externalId ? { [externalId]: nodeHoverAmountsRef.current[externalId] ?? 1 } : {};
    drawGraphRef.current();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphSignature, initialNodePositions]);

  /** pan/zoom 상태와 d3-zoom 내부 transform을 함께 갱신한다. */
  function applyViewState(nextPan: NodePosition, nextZoom: number) {
    graphPanRef.current = nextPan;
    graphZoomRef.current = nextZoom;
    setGraphPan(nextPan);
    setGraphZoom(nextZoom);
    const canvas = canvasRef.current;
    const behavior = zoomBehaviorRef.current;
    if (canvas && behavior) {
      select(canvas).call(behavior.transform, panToTransform(nextPan, nextZoom, canvas));
    }
  }

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

  // graphSignature를 ref로 유지해 안정 참조 콜백(d3-zoom, RAF)에서 최신 서명으로 캐시를 기록한다.
  const graphSignatureRef = useRef(graphSignature);
  useEffect(() => {
    graphSignatureRef.current = graphSignature;
  }, [graphSignature]);

  const scheduleGraphCacheWrite = useCallback(() => {
    if (typeof window === "undefined") return;
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);

    cacheWriteRef.current = window.setTimeout(() => {
      if (simulationRef.current) {
        writeStoredGraphCache({
          signature: graphSignatureRef.current,
          positions: simulationPositions(simulationRef.current),
          pan: graphPanRef.current,
          zoom: graphZoomRef.current
        });
      }
      cacheWriteRef.current = null;
    }, GRAPH_CACHE_DEBOUNCE_MS);
  }, []);

  // d3-zoom: wheel 줌(포인터 중심) + 빈 영역 드래그 pan + 터치 핀치를 처리한다.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const behavior = zoom<HTMLCanvasElement, unknown>()
      .scaleExtent([GRAPH_ZOOM.min, GRAPH_ZOOM.max])
      .filter((event) => {
        if (event.type === "wheel") return true;
        if (event.type === "dblclick") return false; // 더블클릭은 노드 미리보기에 사용
        const button = typeof event.button === "number" ? event.button : 0;
        if (button !== 0 && button !== 1) return false;
        // 노드 위에서 시작한 제스처는 노드 드래그가 처리한다.
        return !hitTestNodeRef.current(event.clientX ?? 0, event.clientY ?? 0);
      })
      .constrain((transform) => {
        const canvasEl = canvasRef.current;
        if (!canvasEl) return transform;
        const pan = transformToPan(transform, canvasEl);
        const clamped = clampGraphPan({ canvas: canvasEl, pan, zoom: transform.k });
        if (clamped.x === pan.x && clamped.y === pan.y) return transform;
        return panToTransform(clamped, transform.k, canvasEl);
      })
      .on("zoom", (event) => {
        const canvasEl = canvasRef.current;
        if (!canvasEl) return;
        const nextPan = transformToPan(event.transform, canvasEl);
        graphZoomRef.current = event.transform.k;
        graphPanRef.current = nextPan;
        setGraphZoom(event.transform.k);
        setGraphPan(nextPan);
        scheduleGraphCacheWrite();
      });

    zoomBehaviorRef.current = behavior;
    const selection = select(canvas);
    selection.call(behavior);
    selection.call(behavior.transform, panToTransform(graphPanRef.current, graphZoomRef.current, canvas));

    const observer = new ResizeObserver(() => {
      const behaviorNow = zoomBehaviorRef.current;
      const canvasNow = canvasRef.current;
      if (!behaviorNow || !canvasNow) return;
      // 리사이즈 후 constrain을 다시 적용해 pan 범위를 보정한다.
      select(canvasNow).call(
        behaviorNow.transform,
        panToTransform(graphPanRef.current, graphZoomRef.current, canvasNow)
      );
      drawGraphRef.current();
    });
    observer.observe(canvas);

    return () => {
      observer.disconnect();
      selection.on(".zoom", null);
      zoomBehaviorRef.current = null;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    const draggedId = draggingNodeIdRef.current;
    if (draggedId) {
      const simNode = simulationRef.current?.nodeById.get(draggedId);
      if (simNode) {
        simNode.fx = null;
        simNode.fy = null;
      }
    }
    setDraggingNodeId(null);
    draggingNodeIdRef.current = null;
  }, []);

  useEffect(() => {
    const stopActiveDrag = (event?: PointerEvent) => stopDragging(event?.pointerId);
    const stopDragOnBlur = () => stopDragging();

    window.addEventListener("pointerup", stopActiveDrag);
    window.addEventListener("pointercancel", stopActiveDrag);
    window.addEventListener("blur", stopDragOnBlur);
    return () => {
      window.removeEventListener("pointerup", stopActiveDrag);
      window.removeEventListener("pointercancel", stopActiveDrag);
      window.removeEventListener("blur", stopDragOnBlur);
    };
  }, [stopDragging]);

  function graphToCanvas(position: NodePosition, canvas: HTMLCanvasElement) {
    return graphToCanvasPosition({ position, canvas, pan: graphPanRef.current, zoom: graphZoomRef.current });
  }

  function hitTestNode(clientX: number, clientY: number): GraphNode | null {
    const canvas = canvasRef.current;
    if (!canvas) return null;

    const rect = canvas.getBoundingClientRect();
    const pointerX = clientX - rect.left;
    const pointerY = clientY - rect.top;

    for (let index = visibleNodeCountRef.current - 1; index >= 0; index -= 1) {
      const node = nodes[index];
      const screenPosition = graphToCanvas(getNodePosition(node.id), canvas);
      const radius = nodeSize(node) / 2;
      const distance = Math.hypot(pointerX - screenPosition.x, pointerY - screenPosition.y);
      if (distance <= radius + 2) return node;
    }

    return null;
  }

  function drawGraph() {
    const canvas = canvasRef.current;
    if (!canvas) return;

    drawGraphFrame({
      canvas,
      nodes,
      links,
      visibleNodeCount: visibleNodeCountRef.current,
      getNodePosition,
      nodeHoverAmounts: nodeHoverAmountsRef.current,
      graphToCanvas,
      nodeSize,
      isRawSourceLink
    });
  }

  // render 중 ref 할당을 피하기 위해 매 렌더 후 layout effect에서 갱신한다.
  useLayoutEffect(() => {
    drawGraphRef.current = drawGraph;
    hitTestNodeRef.current = hitTestNode;
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
    return clampGraphPosition({ position, node, nodeSize: node ? FIXED_NODE_SIZE : 0 });
  }

  function tickGraph(): boolean {
    const graphSimulation = simulationRef.current;
    if (!graphSimulation) return false;
    return tickGraphSimulation(graphSimulation, draggingNodeIdRef.current !== null);
  }

  tickGraphRef.current = tickGraph;

  useGraphAnimation({
    tickGraphRef,
    advanceHoverAnimationRef,
    drawGraphRef,
    scheduleGraphCacheWrite
  });

  const graphCanvasProps = useGraphPointer({
    nodes,
    links,
    draggingNodeIdRef,
    isPointerHeldRef,
    activePointerIdRef,
    externalFocusedNodeIdRef,
    drawGraphRef,
    hitTestNode,
    getSimNode,
    getSimulation: () => simulationRef.current,
    canvasToGraph: (clientX: number, clientY: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return null;
      return canvasToGraphPosition({ clientX, clientY, canvas, pan: graphPanRef.current, zoom: graphZoomRef.current });
    },
    clampPosition,
    scheduleGraphCacheWrite,
    setDraggingNodeId,
    onOpenNodePreview,
    setHoveredNode,
    setSelectedNode,
    stopDragging
  });

  return {
    canvasRef,
    graphCanvasProps
  };
}

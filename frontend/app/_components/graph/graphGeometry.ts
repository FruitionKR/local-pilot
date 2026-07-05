import type { GraphNode, NodePosition } from "../../_lib/types";
import { GRAPH_CENTER, GRAPH_HEIGHT, GRAPH_WIDTH, GRAPH_ZOOM } from "../../_lib/graph";

const GRAPH_VIEW_PADDING = 80;
const GRAPH_PAN_MARGIN = 48;

export function clampGraphZoom(nextZoom: number) {
  return Math.min(GRAPH_ZOOM.max, Math.max(GRAPH_ZOOM.min, nextZoom));
}

/** 드래그 노드로부터의 그래프 거리별 이동 영향도 (거리 4 이상은 0.03) */
const DISTANCE_INFLUENCE = [1, 0.34, 0.16, 0.07, 0.03] as const;

export function graphDistanceInfluence(distance: number | undefined) {
  if (distance === undefined) return 0;
  return DISTANCE_INFLUENCE[distance] ?? 0.03;
}

export function canvasWorldScale(canvas: HTMLCanvasElement, zoom: number) {
  const cssWidth = canvas.clientWidth || canvas.width;
  const cssHeight = canvas.clientHeight || canvas.height;
  const fitWidth = Math.max(1, cssWidth - GRAPH_VIEW_PADDING * 2) / GRAPH_WIDTH;
  const fitHeight = Math.max(1, cssHeight - GRAPH_VIEW_PADDING * 2) / GRAPH_HEIGHT;
  return Math.max(0.1, Math.min(fitWidth, fitHeight)) * zoom;
}

export function clampGraphPan({
  canvas,
  pan,
  zoom
}: {
  canvas: HTMLCanvasElement;
  pan: NodePosition;
  zoom: number;
}) {
  const scale = canvasWorldScale(canvas, zoom);
  const maxX = panLimit(canvas.clientWidth, GRAPH_WIDTH * scale);
  const maxY = panLimit(canvas.clientHeight, GRAPH_HEIGHT * scale);

  return {
    x: Math.min(maxX, Math.max(-maxX, pan.x)),
    y: Math.min(maxY, Math.max(-maxY, pan.y))
  };
}

export function graphToCanvasPosition({
  position,
  canvas,
  pan,
  zoom
}: {
  position: NodePosition;
  canvas: HTMLCanvasElement;
  pan: NodePosition;
  zoom: number;
}) {
  const scale = canvasWorldScale(canvas, zoom);
  return {
    x: canvas.clientWidth / 2 + pan.x + (position.x - GRAPH_CENTER.x) * scale,
    y: canvas.clientHeight / 2 + pan.y + (position.y - GRAPH_CENTER.y) * scale
  };
}

export function canvasToGraphPosition({
  clientX,
  clientY,
  canvas,
  pan,
  zoom
}: {
  clientX: number;
  clientY: number;
  canvas: HTMLCanvasElement;
  pan: NodePosition;
  zoom: number;
}) {
  const rect = canvas.getBoundingClientRect();
  const scale = canvasWorldScale(canvas, zoom);
  return {
    x: GRAPH_CENTER.x + (clientX - rect.left - canvas.clientWidth / 2 - pan.x) / scale,
    y: GRAPH_CENTER.y + (clientY - rect.top - canvas.clientHeight / 2 - pan.y) / scale
  };
}

export function clampGraphPosition({
  position,
  node,
  nodeSize
}: {
  position: NodePosition;
  node?: GraphNode;
  nodeSize: number;
}) {
  const margin = node ? nodeSize / 2 + 4 : 0;
  return {
    x: Math.min(GRAPH_WIDTH - margin, Math.max(margin, position.x)),
    y: Math.min(GRAPH_HEIGHT - margin, Math.max(margin, position.y))
  };
}

function panLimit(viewportSize: number, contentSize: number) {
  if (contentSize <= viewportSize - GRAPH_PAN_MARGIN * 2) {
    return Math.max(0, (viewportSize - contentSize) / 2 - GRAPH_PAN_MARGIN);
  }

  return Math.max(0, (contentSize - viewportSize) / 2 + GRAPH_PAN_MARGIN);
}

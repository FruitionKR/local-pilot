import type { GraphLink, GraphNode, NodePosition } from "../../_lib/types";
import { GRAPH_COLORS, hexToRgb, mixHexColor } from "./graphColors";
import rawNodeIcon from "../../../svg/graph/raw.svg";

const RAW_NODE_ICON_SRC = rawNodeIcon.src;

// 선택 마커 geometry 값 (Figma 디자인에서 가져온 수치)
const SELECTED_MARKER_OUTER_RADIUS = 34.6542;
const SELECTED_MARKER_INNER_RADIUS = 6.5134;
const SELECTED_MARKER_GRADIENT_INNER_RATIO = 0.350403;

// GRAPH_COLORS.hoverNode(#ffc117) 기반 glow gradient 색상
const HOVER_GLOW_RGB = hexToRgb(GRAPH_COLORS.hoverNode).join(", ");
const HOVER_GLOW_START = `rgba(${HOVER_GLOW_RGB}, 0.24)`;
const HOVER_GLOW_END = `rgba(${HOVER_GLOW_RGB}, 0)`;

let rawNodeImage: HTMLImageElement | null = null;

export function drawGraphFrame({
  canvas,
  nodes,
  links,
  visibleNodeCount,
  getNodePosition,
  nodeHoverAmounts,
  graphToCanvas,
  nodeSize,
  isRawSourceLink
}: {
  canvas: HTMLCanvasElement;
  nodes: GraphNode[];
  links: GraphLink[];
  visibleNodeCount: number;
  getNodePosition: (nodeId: string) => NodePosition;
  nodeHoverAmounts: Record<string, number>;
  graphToCanvas: (position: NodePosition, canvas: HTMLCanvasElement) => NodePosition;
  nodeSize: (node: GraphNode) => number;
  isRawSourceLink: (link: GraphLink) => boolean;
}) {
  ensureCanvasSize(canvas);
  const pixelRatio = window.devicePixelRatio || 1;
  const cssWidth = canvas.clientWidth;
  const cssHeight = canvas.clientHeight;

  const context = canvas.getContext("2d");
  if (!context) return;

  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  context.clearRect(0, 0, cssWidth, cssHeight);
  const drawableNodeCount = Math.min(visibleNodeCount, nodes.length);
  const visibleNodeIds = new Set(nodes.slice(0, drawableNodeCount).map((node) => node.id));
  const { linkedHoverAmounts, activeHoverAmount } = computeLinkedHoverAmounts({
    nodes,
    links,
    drawableNodeCount,
    visibleNodeIds,
    nodeHoverAmounts
  });

  function focusAmountFromHover(hoverAmount: number) {
    return Math.min(1, Math.max(0, 1 - activeHoverAmount + hoverAmount));
  }

  for (const link of links) {
    if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
    const from = getNodePosition(link.from);
    const to = getNodePosition(link.to);
    const fromScreen = graphToCanvas(from, canvas);
    const toScreen = graphToCanvas(to, canvas);
    const rawSourceLink = isRawSourceLink(link);
    const baseAlpha = rawSourceLink ? 0.68 : 0.5;
    const fadedAlpha = rawSourceLink ? 0.14 : 0.08;
    const linkHoverAmount = Math.max(nodeHoverAmounts[link.from] ?? 0, nodeHoverAmounts[link.to] ?? 0);
    const focusAmount = focusAmountFromHover(linkHoverAmount);

    context.save();
    context.globalAlpha = fadedAlpha + (baseAlpha - fadedAlpha) * focusAmount;
    context.beginPath();
    context.moveTo(fromScreen.x, fromScreen.y);
    context.lineTo(toScreen.x, toScreen.y);
    context.setLineDash(link.dashed ? [4, 4] : []);
    context.lineWidth = rawSourceLink ? 1.25 : 1.15;
    context.strokeStyle = rawSourceLink ? GRAPH_COLORS.rawSourceLink : GRAPH_COLORS.baseLink;
    context.stroke();

    if (linkHoverAmount > 0.01) {
      context.globalAlpha = 0.98 * linkHoverAmount;
      context.setLineDash([]);
      context.lineWidth = 2.6;
      context.strokeStyle = GRAPH_COLORS.hoverLink;
      context.beginPath();
      context.moveTo(fromScreen.x, fromScreen.y);
      context.lineTo(toScreen.x, toScreen.y);
      context.stroke();
    }

    context.restore();
  }
  context.setLineDash([]);

  for (let index = 0; index < drawableNodeCount; index += 1) {
    const node = nodes[index];
    const position = getNodePosition(node.id);
    const screenPosition = graphToCanvas(position, canvas);
    const radius = nodeSize(node) / 2;
    const hoverAmount = nodeHoverAmounts[node.id] ?? 0;
    const focusAmount = focusAmountFromHover(linkedHoverAmounts.get(node.id) ?? 0);
    const nodeAlpha = 0.16 + 0.84 * focusAmount;

    context.save();
    context.globalAlpha = nodeAlpha;

    if (hoverAmount > 0.01) {
      drawSelectedNodeMarker(context, screenPosition.x, screenPosition.y, hoverAmount);
      context.globalAlpha = nodeAlpha;
    }

    context.beginPath();
    context.arc(screenPosition.x, screenPosition.y, radius, 0, Math.PI * 2);
    if (node.kind === "source") {
      context.fillStyle = mixHexColor(GRAPH_COLORS.sourcePage, GRAPH_COLORS.hoverNode, hoverAmount);
      context.fill();
    } else if (node.kind === "raw") {
      const rawImage = getRawNodeImage();
      if (rawImage) {
        const imageSize = Math.max(12, radius * 2);
        context.drawImage(rawImage, screenPosition.x - imageSize / 2, screenPosition.y - imageSize / 2, imageSize, imageSize);
      } else {
        context.fillStyle = GRAPH_COLORS.rawNodeFill;
        context.fill();
        context.strokeStyle = GRAPH_COLORS.rawNodeStroke;
        context.lineWidth = 1.2;
        context.setLineDash([3, 2.5]);
        context.stroke();
        context.setLineDash([]);
      }

      context.fillStyle = GRAPH_COLORS.hoverNode;
      context.globalAlpha = nodeAlpha * hoverAmount;
      context.fill();
      context.globalAlpha = nodeAlpha;
    } else {
      context.fillStyle = mixHexColor(GRAPH_COLORS.conceptPage, GRAPH_COLORS.hoverNode, hoverAmount);
      context.fill();
    }

    drawNodeLabel(context, node, screenPosition.x, screenPosition.y, radius);
    context.restore();
  }
}

/** canvas 크기를 devicePixelRatio에 맞춰 갱신한다. 크기가 바뀌었으면 true를 반환한다. */
function ensureCanvasSize(canvas: HTMLCanvasElement): boolean {
  const pixelRatio = window.devicePixelRatio || 1;
  const nextWidth = Math.max(1, Math.floor(canvas.clientWidth * pixelRatio));
  const nextHeight = Math.max(1, Math.floor(canvas.clientHeight * pixelRatio));
  if (canvas.width === nextWidth && canvas.height === nextHeight) return false;

  canvas.width = nextWidth;
  canvas.height = nextHeight;
  return true;
}

/** 링크로 연결된 노드까지 hover 강도를 전파해 노드별 최대값과 전체 최대값을 계산한다. */
function computeLinkedHoverAmounts({
  nodes,
  links,
  drawableNodeCount,
  visibleNodeIds,
  nodeHoverAmounts
}: {
  nodes: GraphNode[];
  links: GraphLink[];
  drawableNodeCount: number;
  visibleNodeIds: Set<string>;
  nodeHoverAmounts: Record<string, number>;
}) {
  const linkedHoverAmounts = new Map<string, number>();
  let activeHoverAmount = 0;

  nodes.slice(0, drawableNodeCount).forEach((node) => {
    const hoverAmount = nodeHoverAmounts[node.id] ?? 0;
    linkedHoverAmounts.set(node.id, hoverAmount);
    activeHoverAmount = Math.max(activeHoverAmount, hoverAmount);
  });

  links.forEach((link) => {
    if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) return;
    const fromHoverAmount = nodeHoverAmounts[link.from] ?? 0;
    const toHoverAmount = nodeHoverAmounts[link.to] ?? 0;

    linkedHoverAmounts.set(
      link.from,
      Math.max(linkedHoverAmounts.get(link.from) ?? 0, fromHoverAmount, toHoverAmount)
    );
    linkedHoverAmounts.set(
      link.to,
      Math.max(linkedHoverAmounts.get(link.to) ?? 0, fromHoverAmount, toHoverAmount)
    );
  });

  return { linkedHoverAmounts, activeHoverAmount };
}

export function drawNodeLabel(context: CanvasRenderingContext2D, node: GraphNode, x: number, y: number, radius: number) {
  const labelY = node.kind === "source" ? y + 24 : node.kind === "raw" ? y + 15 : y + 20;
  context.font = node.kind === "source"
    ? "600 14px Pretendard, Inter, sans-serif"
    : node.kind === "raw"
      ? "500 10px Pretendard, Inter, sans-serif"
      : "500 12px Pretendard, Inter, sans-serif";
  context.textAlign = "center";
  context.textBaseline = "middle";

  if (node.kind === "source") {
    context.fillStyle = GRAPH_COLORS.sourceLabelInk;
    context.fillText(node.label, x, labelY);
    return;
  }

  context.fillStyle = GRAPH_COLORS.conceptLabelMuted;
  context.fillText(node.label, x, labelY);
}

function drawSelectedNodeMarker(context: CanvasRenderingContext2D, x: number, y: number, opacity: number) {
  const outerRadius = SELECTED_MARKER_OUTER_RADIUS;
  const innerRadius = SELECTED_MARKER_INNER_RADIUS;
  const gradient = context.createRadialGradient(x, y, outerRadius * SELECTED_MARKER_GRADIENT_INNER_RATIO, x, y, outerRadius);

  gradient.addColorStop(0, HOVER_GLOW_START);
  gradient.addColorStop(1, HOVER_GLOW_END);

  context.globalAlpha = opacity;
  context.fillStyle = gradient;
  context.beginPath();
  context.arc(x, y, outerRadius, 0, Math.PI * 2);
  context.fill();

  context.fillStyle = GRAPH_COLORS.hoverNode;
  context.beginPath();
  context.arc(x, y, innerRadius, 0, Math.PI * 2);
  context.fill();
}

function getRawNodeImage() {
  if (typeof window === "undefined") return null;
  if (!rawNodeImage) {
    rawNodeImage = new window.Image();
    rawNodeImage.src = RAW_NODE_ICON_SRC;
  }

  return rawNodeImage.complete ? rawNodeImage : null;
}

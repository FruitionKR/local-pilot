import type { GraphLink, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";

const SOURCE_PAGE_COLOR = "#bbcf6c";
const CONCEPT_PAGE_COLOR = "#fffdf0";
const HOVER_NODE_COLOR = "#ffc117";

export function drawGraphFrame({
  canvas,
  nodes,
  links,
  visibleNodeCount,
  positions,
  initialNodePositions,
  nodeHoverAmounts,
  graphToCanvas,
  nodeSize,
  isRawSourceLink
}: {
  canvas: HTMLCanvasElement;
  nodes: GraphNode[];
  links: GraphLink[];
  visibleNodeCount: number;
  positions: NodePositionMap;
  initialNodePositions: NodePositionMap;
  nodeHoverAmounts: Record<string, number>;
  graphToCanvas: (position: NodePosition, canvas: HTMLCanvasElement) => NodePosition;
  nodeSize: (node: GraphNode) => number;
  isRawSourceLink: (link: GraphLink) => boolean;
}) {
  const pixelRatio = window.devicePixelRatio || 1;
  const cssWidth = canvas.clientWidth;
  const cssHeight = canvas.clientHeight;
  const nextWidth = Math.max(1, Math.floor(cssWidth * pixelRatio));
  const nextHeight = Math.max(1, Math.floor(cssHeight * pixelRatio));
  if (canvas.width !== nextWidth || canvas.height !== nextHeight) {
    canvas.width = nextWidth;
    canvas.height = nextHeight;
  }

  const context = canvas.getContext("2d");
  if (!context) return;

  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  context.clearRect(0, 0, cssWidth, cssHeight);
  const drawableNodeCount = Math.min(visibleNodeCount, nodes.length);
  const visibleNodeIds = new Set(nodes.slice(0, drawableNodeCount).map((node) => node.id));
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

  function focusAmountFromHover(hoverAmount: number) {
    return Math.min(1, Math.max(0, 1 - activeHoverAmount + hoverAmount));
  }

  for (const link of links) {
    if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
    const from = positions[link.from] ?? initialNodePositions[link.from];
    const to = positions[link.to] ?? initialNodePositions[link.to];
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
    context.strokeStyle = rawSourceLink ? "#5a5a5a" : "#4f4f4f";
    context.stroke();

    if (linkHoverAmount > 0.01) {
      context.globalAlpha = 0.98 * linkHoverAmount;
      context.setLineDash([]);
      context.lineWidth = 2.6;
      context.strokeStyle = "#ffc117";
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
    const position = positions[node.id] ?? initialNodePositions[node.id];
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
      context.fillStyle = mixHexColor(SOURCE_PAGE_COLOR, HOVER_NODE_COLOR, hoverAmount);
      context.fill();
    } else if (node.kind === "raw") {
      context.fillStyle = HOVER_NODE_COLOR;
      context.globalAlpha = nodeAlpha * hoverAmount;
      context.fill();
      context.globalAlpha = nodeAlpha;
      context.strokeStyle = "#6c6c6c";
      context.lineWidth = 1.2;
      context.setLineDash([4, 4]);
      context.stroke();
      context.setLineDash([]);
    } else {
      context.fillStyle = mixHexColor(CONCEPT_PAGE_COLOR, HOVER_NODE_COLOR, hoverAmount);
      context.fill();
    }

    drawNodeLabel(context, node, screenPosition.x, screenPosition.y, radius);
    context.restore();
  }
}

export function drawNodeLabel(context: CanvasRenderingContext2D, node: GraphNode, x: number, y: number, radius: number) {
  const labelY = y + radius + 16;
  context.font = node.kind === "source" ? "600 12px Inter, sans-serif" : "11px Inter, sans-serif";
  context.textAlign = "center";
  context.textBaseline = "middle";

  if (node.kind === "source") {
    context.fillStyle = "#f0f0f0";
    context.fillText(node.label, x, labelY);
    return;
  }

  context.fillStyle = "#8a8a8a";
  context.fillText(node.label, x, labelY);
}

function drawSelectedNodeMarker(context: CanvasRenderingContext2D, x: number, y: number, opacity: number) {
  const outerRadius = 34.6542;
  const innerRadius = 6.5134;
  const gradient = context.createRadialGradient(x, y, outerRadius * 0.350403, x, y, outerRadius);

  gradient.addColorStop(0, "rgba(255, 193, 23, 0.24)");
  gradient.addColorStop(1, "rgba(255, 193, 23, 0)");

  context.globalAlpha = opacity;
  context.fillStyle = gradient;
  context.beginPath();
  context.arc(x, y, outerRadius, 0, Math.PI * 2);
  context.fill();

  context.fillStyle = "#ffc117";
  context.beginPath();
  context.arc(x, y, innerRadius, 0, Math.PI * 2);
  context.fill();
}

function mixHexColor(from: string, to: string, amount: number) {
  const fromRgb = hexToRgb(from);
  const toRgb = hexToRgb(to);
  const red = Math.round(fromRgb[0] + (toRgb[0] - fromRgb[0]) * amount);
  const green = Math.round(fromRgb[1] + (toRgb[1] - fromRgb[1]) * amount);
  const blue = Math.round(fromRgb[2] + (toRgb[2] - fromRgb[2]) * amount);
  return `rgb(${red}, ${green}, ${blue})`;
}

function hexToRgb(hex: string): [number, number, number] {
  const normalized = hex.replace("#", "");
  return [
    parseInt(normalized.slice(0, 2), 16),
    parseInt(normalized.slice(2, 4), 16),
    parseInt(normalized.slice(4, 6), 16)
  ];
}

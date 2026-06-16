import type { GraphLink, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { FOCUS_TRANSITION_MS } from "../../_lib/graph";

type FocusTransition = {
  from: string | null;
  to: string | null;
  startedAt: number;
};

export function drawGraphFrame({
  canvas,
  nodes,
  links,
  visibleNodeCount,
  positions,
  initialNodePositions,
  focusTransition,
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
  focusTransition: FocusTransition;
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
  const rawProgress = Math.min(1, Math.max(0, (performance.now() - focusTransition.startedAt) / FOCUS_TRANSITION_MS));
  const transitionProgress = rawProgress * rawProgress * (3 - rawProgress * 2);

  function directNodeIds(focusNodeId: string | null) {
    const focusedNodeIds = new Set<string>();
    if (!focusNodeId || !visibleNodeIds.has(focusNodeId)) return focusedNodeIds;
    focusedNodeIds.add(focusNodeId);
    for (const link of links) {
      if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
      if (link.from !== focusNodeId && link.to !== focusNodeId) continue;
      focusedNodeIds.add(link.from === focusNodeId ? link.to : link.from);
    }
    return focusedNodeIds;
  }

  const previousFocusedNodeIds = directNodeIds(focusTransition.from);
  const nextFocusedNodeIds = directNodeIds(focusTransition.to);

  function mix(previous: number, next: number) {
    return previous + (next - previous) * transitionProgress;
  }

  function nodeFocusAmount(nodeId: string) {
    const previous = focusTransition.from ? (previousFocusedNodeIds.has(nodeId) ? 1 : 0) : 1;
    const next = focusTransition.to ? (nextFocusedNodeIds.has(nodeId) ? 1 : 0) : 1;
    return mix(previous, next);
  }

  function nodeSelectedAmount(nodeId: string) {
    return mix(focusTransition.from === nodeId ? 1 : 0, focusTransition.to === nodeId ? 1 : 0);
  }

  function linkFocusAmount(link: GraphLink) {
    const previous = focusTransition.from ? (link.from === focusTransition.from || link.to === focusTransition.from ? 1 : 0) : 1;
    const next = focusTransition.to ? (link.from === focusTransition.to || link.to === focusTransition.to ? 1 : 0) : 1;
    return mix(previous, next);
  }

  function linkHighlightAmount(link: GraphLink) {
    return mix(
      focusTransition.from && (link.from === focusTransition.from || link.to === focusTransition.from) ? 1 : 0,
      focusTransition.to && (link.from === focusTransition.to || link.to === focusTransition.to) ? 1 : 0
    );
  }

  for (const link of links) {
    if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
    const from = positions[link.from] ?? initialNodePositions[link.from];
    const to = positions[link.to] ?? initialNodePositions[link.to];
    const fromScreen = graphToCanvas(from, canvas);
    const toScreen = graphToCanvas(to, canvas);
    const rawSourceLink = isRawSourceLink(link);
    const focusAmount = linkFocusAmount(link);
    const highlightAmount = linkHighlightAmount(link);
    const baseAlpha = rawSourceLink ? 0.68 : 0.5;
    const fadedAlpha = rawSourceLink ? 0.14 : 0.08;

    context.save();
    context.globalAlpha = fadedAlpha + (baseAlpha - fadedAlpha) * focusAmount;
    context.beginPath();
    context.moveTo(fromScreen.x, fromScreen.y);
    context.lineTo(toScreen.x, toScreen.y);
    context.setLineDash(link.dashed ? [4, 4] : []);
    context.lineWidth = rawSourceLink ? 1.25 : 1.15;
    context.strokeStyle = rawSourceLink ? "#5a5a5a" : "#4f4f4f";
    context.stroke();

    if (highlightAmount > 0.01) {
      context.globalAlpha = 0.98 * highlightAmount;
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
    const selectedAmount = nodeSelectedAmount(node.id);
    const focusAmount = nodeFocusAmount(node.id);

    context.save();
    context.globalAlpha = 0.16 + 0.84 * focusAmount;

    if (selectedAmount > 0.01) {
      drawSelectedNodeMarker(context, screenPosition.x, screenPosition.y, selectedAmount);
      context.globalAlpha = 0.16 + 0.84 * focusAmount;
    }

    context.beginPath();
    context.arc(screenPosition.x, screenPosition.y, radius, 0, Math.PI * 2);
    if (node.kind === "source") {
      context.fillStyle = "#ffc117";
      context.fill();
      if (node.loading) {
        const spin = performance.now() / 720;
        context.strokeStyle = "rgba(255, 255, 255, 0.58)";
        context.lineWidth = 3;
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius - 5, spin, spin + Math.PI * 1.35);
        context.stroke();
      }
    } else if (node.kind === "raw") {
      context.strokeStyle = "#6c6c6c";
      context.lineWidth = 1.2;
      context.setLineDash([4, 4]);
      context.stroke();
      context.setLineDash([]);
      if (node.loading) {
        const spin = performance.now() / 720;
        context.strokeStyle = "#ffc117";
        context.lineWidth = 2.4;
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius - 2, spin, spin + Math.PI * 1.35);
        context.stroke();
      }
    } else if (node.kind === "progress") {
      const spin = performance.now() / 720;
      context.fillStyle = "#1e1e1e";
      context.fill();
      context.strokeStyle = "rgba(255, 193, 23, 0.24)";
      context.lineWidth = 2;
      context.stroke();
      context.strokeStyle = "#ffc117";
      context.lineWidth = 4;
      context.beginPath();
      context.arc(screenPosition.x, screenPosition.y, radius - 3, spin, spin + Math.PI * 1.35);
      context.stroke();
    } else {
      context.fillStyle = "#646464";
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

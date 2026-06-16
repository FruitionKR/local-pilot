import type { GraphCache, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { GRAPH_CACHE_KEY } from "../../_lib/graph";

export function readStoredGraphCache({
  signature,
  nodes
}: {
  signature: string;
  nodes: GraphNode[];
}) {
  if (typeof window === "undefined") return null;

  try {
    const rawCache = window.localStorage.getItem(GRAPH_CACHE_KEY);
    if (!rawCache) return null;

    const cache = JSON.parse(rawCache) as Partial<GraphCache>;
    if (cache.signature !== signature || !cache.positions || !cache.pan || typeof cache.zoom !== "number") {
      return null;
    }

    const hasEveryNode = nodes.every((node) => {
      const position = cache.positions?.[node.id];
      return typeof position?.x === "number" && typeof position?.y === "number";
    });

    return hasEveryNode ? cache as GraphCache : null;
  } catch {
    return null;
  }
}

export function writeStoredGraphCache({
  signature,
  positions,
  pan,
  zoom
}: {
  signature: string;
  positions: NodePositionMap;
  pan: NodePosition;
  zoom: number;
}) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(GRAPH_CACHE_KEY, JSON.stringify({ signature, positions, pan, zoom }));
}

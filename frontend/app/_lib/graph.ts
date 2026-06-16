import type { DocumentItemResponse, GraphLink, GraphNode, WikiGraphResponse } from "./types";

export const GRAPH_WIDTH = 746;
export const GRAPH_HEIGHT = 568;
export const GRAPH_CACHE_KEY = "fruition.graph.layout.v5";
export const FOCUS_TRANSITION_MS = 500;
export const GRAPH_CENTER = { x: GRAPH_WIDTH / 2, y: GRAPH_HEIGHT / 2 };
export const GRAPH_ZOOM = {
  min: 0.62,
  max: 3.2,
  wheelSensitivity: 0.0018
};
export const GRAPH_PHYSICS = {
  centerStrength: 0.0009,
  damping: 0.42,
  settleThreshold: 0.02,
  revealCenterBoost: 1.7,
  revealLinkBoost: 2.8,
  revealDamping: 0.72,
  originStrength: 0,
  repulsionStrength: 0.006,
  repulsionRange: 260,
  collisionRadiusMultiplier: 0.32,
  nodeDistanceMultiplier: 0.72,
  sourceNodeDistanceMultiplier: 1.8,
  linkStrength: 0.026,
  linkDistanceMultiplier: 0.85,
  linkDistance: {
    source: 178,
    raw: 92,
    progress: 132,
    sourceConcept: 88,
    concept: 98,
    fallback: 118
  }
};

export function linkKey(nodeAId: string, nodeBId: string) {
  return nodeAId < nodeBId ? `${nodeAId}:${nodeBId}` : `${nodeBId}:${nodeAId}`;
}

export function graphNodeKind(node: GraphNode) {
  return node.kind ?? "concept";
}

export function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

export function buildGraphFromBackend(documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const backendSourceByDocumentId = new Map(
    (graph.nodes ?? [])
      .filter((node) => node.page_type === "source" && node.id.startsWith("source:"))
      .map((node) => [node.id.replace("source:", ""), node])
  );
  const rawNodes: GraphNode[] = documents.map((document) => ({
    id: `raw:${document.id}`,
    label: document.filename,
    kind: "raw" as const,
    loading: document.status === "processing" || document.status === "uploaded"
  }));
  const sourceNodes: GraphNode[] = documents.map((document) => {
    const backendSource = backendSourceByDocumentId.get(document.id);
    return {
      id: `source:${document.id}`,
      label: backendSource?.title || document.filename,
      kind: "source" as const,
      size: 32,
      loading: document.status === "processing" || document.status === "uploaded" || document.status === "failed"
    };
  });
  const conceptNodes: GraphNode[] = (graph.nodes ?? [])
    .filter((node) => node.page_type !== "source")
    .map((node) => ({
      id: node.id,
      label: node.title || node.slug || node.id,
      kind: "concept" as const
    }));

  const rawSourceLinks: GraphLink[] = documents.map((document) => ({
    from: `raw:${document.id}`,
    to: `source:${document.id}`,
    dashed: document.status !== "completed"
  }));
  const graphLinks: GraphLink[] = (graph.edges ?? []).map((edge) => ({
    from: edge.from_page_id,
    to: edge.to_page_id,
    active: edge.link_type === "source_mentions_concept"
  }));

  return {
    nodes: [...rawNodes, ...sourceNodes, ...conceptNodes],
    links: [...rawSourceLinks, ...graphLinks]
  };
}

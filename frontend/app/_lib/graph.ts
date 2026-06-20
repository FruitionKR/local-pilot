import type { DocumentItemResponse, GraphLink, GraphNode, WikiGraphResponse } from "./types";

// 노드 ID 접두사 상수
export const NODE_PREFIX = {
  source: "source:",
  concept: "concept:",
  raw: "raw:"
} as const;

/**
 * 노드 ID에서 pageType을 반환합니다.
 * source:/concept: 접두사 판정 규칙을 한 곳에서 관리합니다.
 */
export function nodeIdToPageType(nodeId: string): "source" | "concept" | null {
  if (nodeId.startsWith(NODE_PREFIX.source)) return "source";
  if (nodeId.startsWith(NODE_PREFIX.concept)) return "concept";
  return null;
}

/**
 * documentId로 source 노드 ID를 생성합니다.
 */
export function makeSourceId(documentId: string): string {
  return `${NODE_PREFIX.source}${documentId}`;
}

/**
 * raw 노드 ID에서 documentId를 복원합니다.
 */
export function rawNodeIdToDocumentId(nodeId: string): string | null {
  if (!nodeId.startsWith(NODE_PREFIX.raw)) return null;
  return nodeId.slice(NODE_PREFIX.raw.length) || null;
}

export const GRAPH_WIDTH = 746;
export const GRAPH_HEIGHT = 568;
export const GRAPH_CACHE_KEY = "fruition.graph.layout.v7";
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
  originStrength: 0.008,
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

export function buildGraphFromBackend(documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const backendSourceByDocumentId = new Map(
    (graph.nodes ?? [])
      .filter((node) => node.page_type === "source" && node.id.startsWith("source:"))
      .map((node) => [node.id.replace("source:", ""), node])
  );
  const rawNodes: GraphNode[] = documents.map((document) => ({
    id: `raw:${document.id}`,
    label: document.filename,
    kind: "raw" as const
  }));
  const sourceNodes: GraphNode[] = documents.map((document) => {
    const backendSource = backendSourceByDocumentId.get(document.id);
    return {
      id: `source:${document.id}`,
      label: backendSource?.title || document.filename,
      kind: "source" as const
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
